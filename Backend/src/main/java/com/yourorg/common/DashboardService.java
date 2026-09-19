package com.yourorg.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yourorg.Recommendation.Recommendation;
import com.yourorg.Recommendation.RecommendationRepository;
import com.yourorg.Recommendation.RecommendationService;
import com.yourorg.Recommendation.Type;
import com.yourorg.Subscriptions.Subscription;
import com.yourorg.Subscriptions.SubscriptionRepository;
import com.yourorg.Subscriptions.SubscriptionStatus;
import com.yourorg.UserSnapShot.UserSnapShotRepository;
import com.yourorg.UserSnapShot.UserSnapShots;

/**
 * Assembles the dashboard: one row per subscription plus an account-level summary.
 *
 * <p>Three deliberate departures from the previous implementation:
 *
 * <ul>
 *   <li><b>Every subscription is returned</b>, including unused ones. The old code
 *       filtered on {@code usage > 0}, which hid exactly the subscriptions the user
 *       most needs to see -- the ones being paid for and not used.</li>
 *   <li><b>Recommendations are recomputed on read</b> rather than hardcoded to
 *       "PENDING". A read that writes is a trade-off; it is taken here because the
 *       alternative is showing the user a placeholder verdict. The work is one batch
 *       query plus an upsert per subscription. A scheduled job would be the right
 *       answer at larger scale.</li>
 *   <li><b>Two batch queries replace the per-subscription queries</b> that ran
 *       inside the old loop.</li>
 * </ul>
 */
@Service
public class DashboardService {

    @Autowired private SubscriptionRepository subscriptionRepo;
    @Autowired private UserSnapShotRepository snapshotRepo;
    @Autowired private RecommendationRepository recommendationRepo;
    @Autowired private RecommendationService recommendationService;

    @Transactional
    public List<DashBoardResponseDTO> getDashboardData(Long userId, String requestedPeriod) {
        YearMonth period = resolvePeriod(userId, requestedPeriod);
        List<Subscription> subs = subscriptionRepo.findByUserId(userId);
        if (subs.isEmpty()) {
            return List.of();
        }

        recommendationService.recomputeForUser(userId, period);

        Map<Long, UserSnapShots> usage = usageBySubscription(subs, period);
        Map<Long, Recommendation> recs = recommendationsBySubscription(userId);

        List<DashBoardResponseDTO> rows = new ArrayList<>(subs.size());
        for (Subscription sub : subs) {
            UserSnapShots snapshot = usage.get(sub.getId());
            Recommendation rec = recs.get(sub.getId());

            rows.add(DashBoardResponseDTO.builder()
                    .providerName(sub.getProviderName())
                    .packageName(sub.getPackageName())
                    .price(sub.getPrice())
                    .currency(sub.getCurrency())
                    .monthlyCost(sub.getMonthlyPrice())
                    .usageMinutes(snapshot == null ? 0 : snapshot.getUsageCount())
                    .hasUsageData(snapshot != null)
                    .billingPeriod(period.toString())
                    .recommendationType(rec == null ? Type.CONSIDER.name() : rec.getType().name())
                    .reason(rec == null ? "No recommendation has been generated yet." : rec.getReason())
                    .confidenceScore(rec == null ? null : rec.getConfidenceScore())
                    .valueScore(rec == null ? null : rec.getValueScore())
                    .costPerHour(rec == null ? null : rec.getCostPerHour())
                    .estimatedMonthlySavings(rec == null ? null : rec.getEstimatedMonthlySavings())
                    .build());
        }
        return rows;
    }

    /** Account-level totals: spend, waste, and recoverable money. */
    @Transactional
    public DashboardSummaryDTO getSummary(Long userId, String requestedPeriod) {
        YearMonth period = resolvePeriod(userId, requestedPeriod);
        List<Subscription> all = subscriptionRepo.findByUserId(userId);
        List<Subscription> active = subscriptionRepo.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);

        DashboardSummaryDTO.DashboardSummaryDTOBuilder summary = DashboardSummaryDTO.builder()
                .period(period.toString())
                .subscriptionCount(all.size())
                .activeSubscriptionCount(active.size())
                .currency(active.isEmpty() ? null : active.get(0).getCurrency())
                .totalMonthlySpend(BigDecimal.ZERO.setScale(2))
                .totalYearlySpend(BigDecimal.ZERO.setScale(2))
                .potentialMonthlySavings(BigDecimal.ZERO.setScale(2))
                .potentialYearlySavings(BigDecimal.ZERO.setScale(2))
                .wastedSpendPercent(BigDecimal.ZERO.setScale(2))
                .recommendationCounts(new LinkedHashMap<>());

        if (active.isEmpty()) {
            return summary.build();
        }

        recommendationService.recomputeForUser(userId, period);

        Map<Long, UserSnapShots> usage = usageBySubscription(active, period);
        Map<Long, Recommendation> recs = recommendationsBySubscription(userId);

        BigDecimal monthlySpend = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        int totalMinutes = 0;
        int unused = 0;
        int noData = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();

        String bestProvider = null, worstProvider = null;
        BigDecimal bestScore = null, worstScore = null;

        for (Subscription sub : active) {
            monthlySpend = monthlySpend.add(sub.getMonthlyPrice());

            UserSnapShots snapshot = usage.get(sub.getId());
            if (snapshot == null) {
                noData++;
            } else {
                totalMinutes += snapshot.getUsageCount();
                if (snapshot.getUsageCount() == 0) {
                    unused++;
                }
            }

            Recommendation rec = recs.get(sub.getId());
            if (rec == null) {
                continue;
            }
            counts.merge(rec.getType().name(), 1, Integer::sum);
            if (rec.getEstimatedMonthlySavings() != null) {
                savings = savings.add(rec.getEstimatedMonthlySavings());
            }
            BigDecimal score = rec.getValueScore();
            if (score != null) {
                if (bestScore == null || score.compareTo(bestScore) > 0) {
                    bestScore = score;
                    bestProvider = sub.getProviderName();
                }
                if (worstScore == null || score.compareTo(worstScore) < 0) {
                    worstScore = score;
                    worstProvider = sub.getProviderName();
                }
            }
        }

        BigDecimal wastedPercent = monthlySpend.signum() > 0
                ? savings.multiply(new BigDecimal("100")).divide(monthlySpend, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);

        return summary
                .totalMonthlySpend(monthlySpend.setScale(2, RoundingMode.HALF_UP))
                .totalYearlySpend(monthlySpend.multiply(new BigDecimal("12")).setScale(2, RoundingMode.HALF_UP))
                .totalUsageMinutes(totalMinutes)
                .unusedCount(unused)
                .noDataCount(noData)
                .potentialMonthlySavings(savings.setScale(2, RoundingMode.HALF_UP))
                .potentialYearlySavings(savings.multiply(new BigDecimal("12")).setScale(2, RoundingMode.HALF_UP))
                .wastedSpendPercent(wastedPercent)
                .recommendationCounts(counts)
                .bestValueProvider(bestProvider)
                .bestValueScore(bestScore)
                .worstValueProvider(worstProvider)
                .worstValueScore(worstScore)
                .build();
    }

    private Map<Long, UserSnapShots> usageBySubscription(List<Subscription> subs, YearMonth period) {
        List<Long> ids = subs.stream().map(Subscription::getId).toList();
        Map<Long, UserSnapShots> byId = new HashMap<>();
        for (UserSnapShots snap : snapshotRepo.findBySubscriptionIdInAndPeriodIn(ids, List.of(period.toString()))) {
            byId.put(snap.getSubscription().getId(), snap);
        }
        return byId;
    }

    private Map<Long, Recommendation> recommendationsBySubscription(Long userId) {
        Map<Long, Recommendation> byId = new HashMap<>();
        for (Recommendation rec : recommendationRepo.findByUserId(userId)) {
            byId.put(rec.getSubscription().getId(), rec);
        }
        return byId;
    }

    /**
     * Accepts an explicit month (YYYY-MM). Anything else -- blank, or the legacy
     * TODAY/WEEK/MONTH values the Android client used to send -- resolves to the most
     * recent month that actually has usage data, falling back to the current month.
     *
     * <p>This is why the response always states which period it describes: the
     * dashboard shows real data for a real month rather than an empty current month.
     */
    YearMonth resolvePeriod(Long userId, String requestedPeriod) {
        if (requestedPeriod != null && !requestedPeriod.isBlank()) {
            try {
                return YearMonth.parse(requestedPeriod.trim());
            } catch (DateTimeParseException ignored) {
                // Not a month; fall through to the latest period with data.
            }
        }
        String latest = snapshotRepo.findLatestPeriodForUser(userId);
        if (latest != null && !latest.isBlank()) {
            try {
                return YearMonth.parse(latest);
            } catch (DateTimeParseException ignored) {
                // Corrupt period value in the database; ignore it.
            }
        }
        return YearMonth.now();
    }
}
