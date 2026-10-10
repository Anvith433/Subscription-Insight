package com.yourorg.UserSnapShot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * <p><b>Two kinds of report.</b> Current clients send minutes per calendar day
 * ({@link AppUsageDTO#getDays()}). Those are stored in usage_days and each month's
 * snapshot is rebuilt from them, because no single scan can see a whole month -- see
 * V9__daily_usage.sql. Older clients send one total for a TODAY/WEEK/MONTH window;
 * that path is kept, and the rest of this comment describes it.
 *
 * <p><b>Why a window total is merged with max() rather than added:</b> the device
 * reports the <i>total</i> foreground time over the window it is asked about. Adding
 * each sync would double-count the same minutes every time the user pressed Sync, and
 * plain assignment would let a "Today" scan overwrite the month's total with a much
 * smaller number. Taking the maximum is correct for both: a full-month scan raises the
 * total as the month progresses, and a shorter scan can never shrink it.
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
    @Autowired private UsageDayRepository usageDayRepo;
    @Autowired private BillingRecordService billingRecordService;
    @Autowired private RecommendationService recommendationService;

    /**
     * @param userId    the <b>authenticated</b> user. Deliberately a parameter rather
     *                  than read from the request body, so a client cannot write usage
     *                  into somebody else's account.
     * @param rawWindow the span an older client scanned ("TODAY"/"WEEK"/"MONTH"). Only
     *                  read for apps reported as a single total; null or unrecognised
     *                  is read as a full month, which is what the server assumed before
     *                  it started recording this at all.
     */
    @Transactional
    public UsageSyncResult processUsage(Long userId, String rawWindow, List<AppUsageDTO> apps) {
        YearMonth period = YearMonth.now();
        List<String> synced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        if (apps == null || apps.isEmpty()) {
            return new UsageSyncResult(period.toString(), synced, skipped, rejected);
        }

        // Resolved once, so every row in one request describes the same observation
        // even if the clock crosses midnight midway through a long batch.
        final LocalDate today = LocalDate.now();
        final UsageWindow window = UsageWindow.fromClient(rawWindow);
        final int observedDays = window.observedDaysWithin(today);
        final int monthDaysElapsed = UsageWindow.monthDaysElapsed(today);

        for (AppUsageDTO app : apps) {
            Subscription sub = resolveSubscription(userId, app);

            // An untracked app is an expected, routine condition. Skip it. Throwing here
            // (as an earlier implementation did) failed the entire batch because one app
            // in the list happened not to be a subscription.
            if (sub == null) {
                skipped.add(identifierOf(app));
                continue;
            }

            boolean stored = app.getDays() != null && !app.getDays().isEmpty()
                    ? recordDays(sub, app.getDays(), today)
                    : recordWindowTotal(sub, app.getUsageMinutes(), period, window,
                            observedDays, monthDaysElapsed);
            if (stored) {
                synced.add(sub.getProviderName());
            } else {
                rejected.add(sub.getProviderName());
            }
        }

        if (!synced.isEmpty()) {
            // Fresh usage means the previous verdicts are stale.
            recommendationService.recomputeForUser(userId, period);
        }

        log.info("Usage sync for user {} in {}: {} synced, {} skipped, {} rejected",
                userId, period, synced.size(), skipped.size(), rejected.size());
        return new UsageSyncResult(period.toString(), synced, skipped, rejected);
    }

    /**
     * Stores one app's daily figures and rebuilds every month they fall in. A sync
     * early in a month usually reaches back into the previous one, which is how the
     * last days of a finished month get filled in.
     *
     * @return false, storing nothing, when any figure is impossible
     */
    private boolean recordDays(Subscription sub, List<DayUsageDTO> reported, LocalDate today) {
        Map<LocalDate, Integer> days = DailyUsage.validate(reported, today);
        if (days == null) {
            log.warn("Rejected daily usage for subscription {}: a date more than a day ahead, a "
                    + "malformed or repeated date, or minutes outside 0-{}", sub.getId(), DailyUsage.MINUTES_PER_DAY);
            return false;
        }

        LocalDate first = days.keySet().iterator().next();
        Map<LocalDate, UsageDay> existing = new HashMap<>();
        for (UsageDay row : usageDayRepo.findBySubscriptionAndUsageDateBetween(sub, first, today)) {
            existing.put(row.getUsageDate(), row);
        }

        Instant now = Instant.now();
        Set<YearMonth> months = new TreeSet<>();
        for (Map.Entry<LocalDate, Integer> day : days.entrySet()) {
            if (day.getKey().isAfter(today)) {
                // The phone is ahead of the server and already on tomorrow. Not stored
                // until it is a day here too; the next sync sends it again.
                continue;
            }
            UsageDay row = existing.get(day.getKey());
            if (row == null) {
                row = new UsageDay();
                row.setSubscription(sub);
                row.setUsageDate(day.getKey());
                row.setMinutes(day.getValue());
            } else if (day.getValue() > row.getMinutes()) {
                // A finished day reads the same on every scan; only today grows. Keeping
                // the larger figure means a later scan that saw less of a day -- the
                // phone had already deleted part of it -- cannot erase what was measured.
                row.setMinutes(day.getValue());
            }
            row.setUpdatedAt(now);
            usageDayRepo.save(row);
            months.add(YearMonth.from(day.getKey()));
        }

        for (YearMonth month : months) {
            rebuildMonth(sub, month, today);
            // Keep each month's bill in step with the subscriptions actually in use.
            billingRecordService.createBill(sub, month.toString());
        }
        return true;
    }

    /**
     * Rewrites a month's snapshot from its recorded days. The days are the measurement
     * now, so this replaces whatever single-window figure an older client left there.
     */
    private void rebuildMonth(Subscription sub, YearMonth month, LocalDate today) {
        Map<LocalDate, Integer> recorded = new HashMap<>();
        for (UsageDay row : usageDayRepo.findBySubscriptionAndUsageDateBetween(
                sub, month.atDay(1), month.atEndOfMonth())) {
            recorded.put(row.getUsageDate(), row.getMinutes());
        }
        DailyUsage.MonthFigures figures = DailyUsage.rollUp(month, today, recorded);

        UserSnapShots snapshot = snapshotRepo.findBySubscriptionAndPeriod(sub, month.toString())
                .orElseGet(() -> new UserSnapShots(sub, month.toString(), 0, DataSource.ANDROID));
        snapshot.setUsageCount(figures.minutes());
        // MONTH: the row describes the month as assembled from its days. How much of it
        // was actually seen is in the two counts, and those are what the engine reads.
        snapshot.setObservedWindow(UsageWindow.MONTH);
        snapshot.setObservedDays(figures.observedDays());
        snapshot.setMonthDaysElapsed(figures.monthDaysElapsed());
        // The last day with real use, not the time of the sync: stamping the sync time
        // made an app nobody opened read "last used 0 days ago".
        LocalDate lastUsed = usageDayRepo.findLastUsedDate(sub);
        if (lastUsed != null) {
            snapshot.setLastUsedAt(lastUsed.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        snapshot.setSource(DataSource.ANDROID);
        snapshotRepo.save(snapshot);
    }

    /**
     * An older client's single total for a TODAY/WEEK/MONTH window.
     *
     * @return false, storing nothing, when the total could not be true
     */
    private boolean recordWindowTotal(Subscription sub, int reported, YearMonth period,
                                      UsageWindow window, int observedDays, int monthDaysElapsed) {
        // Negative minutes, or more minutes than the window has, are not a measurement.
        // This used to clamp negatives to zero, which turned a broken report into a
        // confident "not used at all".
        if (!DailyUsage.fitsWithin(reported, observedDays)) {
            log.warn("Rejected usage for subscription {}: {} min cannot fit in {} day(s)",
                    sub.getId(), reported, observedDays);
            return false;
        }

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

        // Only a figure above zero is evidence of use. Stamping every sync as a use
        // made an untouched app read "last used 0 days ago".
        if (reported > 0) {
            snapshot.setLastUsedAt(Instant.now());
        }
        // NOT NULL in the schema; omitting it made every insert fail.
        snapshot.setSource(DataSource.ANDROID);
        snapshotRepo.save(snapshot);

        // Keep the month's bill in step with the subscriptions actually in use.
        billingRecordService.createBill(sub, period.toString());
        return true;
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
