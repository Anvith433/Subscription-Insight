package com.yourorg.UserSnapShot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yourorg.BillingRecord.BillingRecordService;
import com.yourorg.Recommendation.RecommendationService;
import com.yourorg.Subscriptions.Subscription;
import com.yourorg.Subscriptions.SubscriptionRepository;
import com.yourorg.common.DataSource;

/**
 * Ingests device usage and drives the rest of the pipeline.
 *
 * <p>Storage model: one snapshot row per (subscription, month), keyed by
 * {@code period} in YYYY-MM form. That matches the user_snapshots.period column
 * (VARCHAR(7)) and its uk_subscription_period unique key.
 *
 * <p><b>Why usage is merged with max() rather than added:</b> Android's
 * UsageStatsManager reports the <i>total</i> foreground time over the window it is
 * asked about. Adding each sync would double-count the same minutes every time the
 * user pressed Sync, and plain assignment would let a "Today" scan overwrite the
 * month's total with a much smaller number. Taking the maximum is correct for both:
 * a full-month scan raises the total as the month progresses, and a shorter scan can
 * never shrink it.
 *
 * <p><b>Why the scan window is stored alongside the figure:</b> max() stops a short
 * scan from shrinking a good total, but it cannot help when there is nothing to
 * compare against. The first sync of a fresh month creates the row, so a "Today"
 * scan's single day used to be filed as the whole month -- and 20 minutes of a
 * 199/month plan reads as 597 per hour, which is a confident CANCEL off one day of
 * data. Each row now also records which span was scanned and how much of the month
 * that covered, so {@link com.yourorg.Recommendation.RecommendationEngine} can
 * answer "at least this much" instead of "exactly this much". The trio is only
 * updated together with {@code usageCount} -- see
 * {@link #shouldReplace(UserSnapShots, int, int, int)}.
 */
@Service
public class UserSnapShotService {

    private static final Logger log = LoggerFactory.getLogger(UserSnapShotService.class);

    @Autowired private SubscriptionRepository subscriptionRepo;
    @Autowired private UserSnapShotRepository snapshotRepo;
    @Autowired private BillingRecordService billingRecordService;
    @Autowired private RecommendationService recommendationService;

    /**
     * @param userId    the <b>authenticated</b> user. Deliberately a parameter rather
     *                  than read from the request body, so a client cannot write usage
     *                  into somebody else's account.
     * @param rawWindow the span the device scanned, as the client named it
     *                  ("TODAY"/"WEEK"/"MONTH"). Null or unrecognised is read as a
     *                  full month, which is what the server assumed before it started
     *                  recording this at all.
     */
    @Transactional
    public UsageSyncResult processUsage(Long userId, String rawWindow, List<AppUsageDTO> apps) {
        YearMonth period = YearMonth.now();
        List<String> synced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        if (apps == null || apps.isEmpty()) {
            return new UsageSyncResult(period.toString(), synced, skipped);
        }

        // Resolved once, so every row in one request describes the same observation
        // even if the clock crosses midnight midway through a long batch.
        final LocalDate today = LocalDate.now();
        final UsageWindow window = UsageWindow.fromClient(rawWindow);
        final int observedDays = window.observedDaysWithin(today);
        final int monthDaysElapsed = UsageWindow.monthDaysElapsed(today);

        for (AppUsageDTO app : apps) {
            Subscription sub = resolveSubscription(userId, app);

            // An untracked app is an expected, routine condition -- the device reports
            // every app, not just subscribed ones. Skip it. Throwing here (as the
            // previous implementation did) failed the entire batch because one app in
            // the list happened not to be a subscription.
            if (sub == null) {
                skipped.add(identifierOf(app));
                continue;
            }

            int reported = Math.max(0, app.getUsageMinutes());

            // A new row always takes the incoming observation, including its coverage.
            // Routing it through shouldReplace() instead would leave a fresh row that
            // reports zero minutes with null coverage -- read as a complete month, and
            // zero minutes over a complete month is exactly the false CANCEL this
            // change exists to prevent.
            UserSnapShots snapshot = snapshotRepo
                    .findBySubscriptionAndPeriod(sub, period.toString())
                    .orElse(null);
            if (snapshot == null) {
                snapshot = new UserSnapShots();
                snapshot.setSubscription(sub);
                snapshot.setPeriod(period.toString());
                record(snapshot, reported, window, observedDays, monthDaysElapsed);
            } else if (shouldReplace(snapshot, reported, observedDays, monthDaysElapsed)) {
                record(snapshot, reported, window, observedDays, monthDaysElapsed);
            }

            snapshot.setLastUsedAt(Instant.now());
            // NOT NULL in the schema; omitting it made every insert fail.
            snapshot.setSource(DataSource.ANDROID);
            snapshotRepo.save(snapshot);

            // Keep the month's bill in step with the subscriptions actually in use.
            billingRecordService.createBill(sub, period.toString());
            synced.add(sub.getProviderName());
        }

        if (!synced.isEmpty()) {
            // Fresh usage means the previous verdicts are stale.
            recommendationService.recomputeForUser(userId, period);
        }

        log.info("Usage sync for user {} in {} ({} window, {}/{} days): {} synced, {} skipped",
                userId, period, window, observedDays, monthDaysElapsed,
                synced.size(), skipped.size());
        return new UsageSyncResult(period.toString(), synced, skipped);
    }

    /**
     * Writes a figure and the coverage it was measured over as one unit. Never split
     * these: a one-day figure carrying a full-month label is the original bug, and a
     * full-month figure carrying a one-day label is the same bug mirrored.
     */
    private void record(UserSnapShots snapshot, int minutes, UsageWindow window,
                        int observedDays, int monthDaysElapsed) {
        snapshot.setUsageCount(minutes);
        snapshot.setObservedWindow(window);
        snapshot.setObservedDays(observedDays);
        snapshot.setMonthDaysElapsed(monthDaysElapsed);
    }

    /**
     * Whether an incoming observation should displace the stored one. Yes when it
     * reports more minutes -- the original max() rule -- and also when it reports the
     * same minutes over more of the month.
     *
     * <p>That second clause is not a refinement, it is required for the feature to
     * terminate. Someone who scans "Today" and then "This Month" often gets the same
     * figure back, because the day they scanned was the only day they used the app.
     * Under max() alone the row would keep its one-day coverage forever and the
     * verdict would stay provisional no matter how many full-month scans followed.
     */
    private boolean shouldReplace(UserSnapShots existing, int reported,
                                  int observedDays, int monthDaysElapsed) {
        if (reported > existing.getUsageCount()) {
            return true;
        }
        if (reported < existing.getUsageCount()) {
            return false;
        }
        return coverageOf(observedDays, monthDaysElapsed) > coverageOf(
                existing.getObservedDays(), existing.getMonthDaysElapsed());
    }

    /**
     * Fraction of the month an observation covered. Null coverage -- any row written
     * before V8 -- reads as 1.0, the same "assume complete" default the engine uses,
     * so a genuinely partial new scan never displaces an older complete one.
     */
    private double coverageOf(Integer observedDays, Integer monthDaysElapsed) {
        if (observedDays == null || monthDaysElapsed == null || monthDaysElapsed <= 0) {
            return 1.0;
        }
        return (double) observedDays / monthDaysElapsed;
    }

    /**
     * Matches an incoming app to a subscription. The package name is tried first
     * because it is a stable OS identifier; the display name is only a fallback for
     * clients that do not send one. Matching on the display name alone is what made
     * "YouTube" fail to match the "YouTube Premium" subscription.
     */
    private Subscription resolveSubscription(Long userId, AppUsageDTO app) {
        if (app.getPackageName() != null && !app.getPackageName().isBlank()) {
            var byPackage = subscriptionRepo
                    .findByUserIdAndPackageNameIgnoreCase(userId, app.getPackageName().trim());
            if (byPackage.isPresent()) {
                return byPackage.get();
            }
        }
        if (app.getAppName() != null && !app.getAppName().isBlank()) {
            return subscriptionRepo
                    .findByUserIdAndProviderNameIgnoreCase(userId, app.getAppName().trim())
                    .orElse(null);
        }
        return null;
    }

    private String identifierOf(AppUsageDTO app) {
        if (app.getPackageName() != null && !app.getPackageName().isBlank()) {
            return app.getPackageName();
        }
        return app.getAppName() == null ? "(unnamed)" : app.getAppName();
    }
}
