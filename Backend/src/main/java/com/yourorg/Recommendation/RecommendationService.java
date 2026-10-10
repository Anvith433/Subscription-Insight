package com.yourorg.Recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yourorg.Subscriptions.Subscription;
import com.yourorg.Subscriptions.SubscriptionRepository;
import com.yourorg.Subscriptions.SubscriptionStatus;
import com.yourorg.UserSnapShot.DailyUsage;
import com.yourorg.UserSnapShot.UserSnapShotRepository;
import com.yourorg.UserSnapShot.UserSnapShots;

/**
 * Turns stored usage into persisted, explainable recommendations.
 *
 * <p>Responsibilities are split deliberately: this class does the database work
 * (batch-load snapshots, compute portfolio context, upsert rows) while
 * {@link RecommendationEngine} owns the decision rules. There is exactly one
 * current recommendation per (user, subscription), so recomputing overwrites the
 * previous verdict rather than appending history.
 */
@Service
public class RecommendationService {

    @Autowired private SubscriptionRepository subscriptionRepo;
    @Autowired private UserSnapShotRepository snapshotRepo;
    @Autowired private RecommendationRepository recommendationRepo;
    @Autowired private RecommendationEngine engine;

    /**
     * Recomputes every active subscription for a user against one month.
     *
     * @return the recommendations that were written, in subscription order
     */
    @Transactional
    public List<Recommendation> recomputeForUser(Long userId, YearMonth period) {
        List<Subscription> subs = subscriptionRepo.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);
        if (subs.isEmpty()) {
            return List.of();
        }

        List<Long> subIds = subs.stream().map(Subscription::getId).toList();
        String current = period.toString();
        String previous = period.minusMonths(1).toString();

        // One query for both months instead of two queries per subscription.
        Map<Long, UserSnapShots> currentBySub = new HashMap<>();
        Map<Long, UserSnapShots> previousBySub = new HashMap<>();
        for (UserSnapShots snap : snapshotRepo.findBySubscriptionIdInAndPeriodIn(subIds, List.of(current, previous))) {
            Long subId = snap.getSubscription().getId();
            if (current.equals(snap.getPeriod())) {
                currentBySub.put(subId, snap);
            } else {
                previousBySub.put(subId, snap);
            }
        }

        BigDecimal median = medianMonthlyPrice(subs);
        LocalDate today = LocalDate.now();

        List<Recommendation> written = new ArrayList<>(subs.size());
        for (Subscription sub : subs) {
            UserSnapShots now = currentBySub.get(sub.getId());
            UserSnapShots prior = previousBySub.get(sub.getId());

            UsageFacts facts = new UsageFacts(
                    sub.getProviderName(),
                    sub.getCurrency(),
                    sub.getMonthlyPrice(),
                    now == null ? null : now.getUsageCount(),
                    comparablePrior(now, prior, period, today),
                    now == null ? null : now.getLastUsedAt(),
                    sub.getStartDate(),
                    period,
                    median,
                    today,
                    // Read from the row, not derived from `today`: this method runs on
                    // dashboard reads as well as after a sync, so deriving coverage
                    // here would describe when the page was opened rather than when
                    // the scan was taken -- and the two paths would disagree.
                    now == null ? null : now.getObservedDays(),
                    now == null ? null : now.getMonthDaysElapsed());

            written.add(upsert(userId, sub, period, facts.usageMinutes(), engine.assess(facts)));
        }
        return written;
    }

    /**
     * Last month's usage scaled to the days this month's figure covers, or null when
     * there is no fair comparison -- see {@link DailyUsage#comparablePrior}. A row
     * with no recorded coverage (written before V8) is read as covering its whole
     * month, or the month so far for the current one, as everywhere else.
     */
    private static Integer comparablePrior(UserSnapShots now, UserSnapShots prior,
                                           YearMonth period, LocalDate today) {
        if (now == null || prior == null) {
            return null;
        }
        YearMonth priorMonth = period.minusMonths(1);
        int priorObserved = prior.getObservedDays() != null ? prior.getObservedDays() : priorMonth.lengthOfMonth();
        int priorElapsed = prior.getMonthDaysElapsed() != null ? prior.getMonthDaysElapsed() : priorMonth.lengthOfMonth();
        int currentDays = now.getObservedDays() != null
                ? now.getObservedDays()
                : (period.equals(YearMonth.from(today)) ? today.getDayOfMonth() : period.lengthOfMonth());
        return DailyUsage.comparablePrior(prior.getUsageCount(), priorObserved, priorElapsed, currentDays);
    }

    /** Recomputes the current month. Called after a usage sync. */
    @Transactional
    public List<Recommendation> recomputeForUser(Long userId) {
        return recomputeForUser(userId, YearMonth.now());
    }

    private Recommendation upsert(Long userId, Subscription sub, YearMonth period,
                                  Integer usageMinutes, Verdict verdict) {
        Recommendation rec = recommendationRepo
                .findByUserIdAndSubscriptionId(userId, sub.getId())
                .orElseGet(() -> {
                    Recommendation fresh = new Recommendation();
                    fresh.setUser(sub.getUser());
                    fresh.setSubscription(sub);
                    return fresh;
                });

        rec.setType(verdict.type());
        rec.setPeriod(period.toString());
        rec.setReason(verdict.reason());
        rec.setUsageMinutes(usageMinutes);
        rec.setMonthlyCost(sub.getMonthlyPrice());
        rec.setCostPerHour(verdict.costPerHour());
        rec.setValueScore(verdict.valueScore());
        rec.setEstimatedMonthlySavings(verdict.estimatedMonthlySavings());
        rec.setConfidenceScore(verdict.confidence());
        rec.setStatus(Status.ACTIVE);
        rec.setGeneratedAt(Instant.now());

        return recommendationRepo.save(rec);
    }

    /**
     * Median monthly price across the user's active subscriptions. Gives the engine
     * the portfolio context it needs to judge whether "switch to a cheaper tier" is
     * a sensible suggestion for a given subscription.
     */
    private BigDecimal medianMonthlyPrice(List<Subscription> subs) {
        List<BigDecimal> prices = subs.stream()
                .map(Subscription::getMonthlyPrice)
                .filter(p -> p != null && p.signum() > 0)
                .sorted(Comparator.naturalOrder())
                .toList();

        if (prices.isEmpty()) {
            return null;
        }
        int mid = prices.size() / 2;
        if (prices.size() % 2 == 1) {
            return prices.get(mid);
        }
        return prices.get(mid - 1).add(prices.get(mid))
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    }
}
