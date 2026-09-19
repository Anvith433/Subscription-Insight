package com.yourorg.common;

import java.math.BigDecimal;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Account-level answers to the questions the product exists to answer: where the
 * money goes, how much of it is wasted, and how much could be recovered.
 *
 * <p>{@code potentialMonthlySavings} only counts CANCEL verdicts, where the saving
 * is exactly the subscription price and therefore certain. DOWNGRADE verdicts are
 * surfaced as a count instead of a rupee figure, because the real saving depends on
 * the provider's cheaper tier and this system has no plan catalogue to look it up.
 */
@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class DashboardSummaryDTO {

    /** Month these figures describe, YYYY-MM. */
    private String period;
    private String currency;

    private int subscriptionCount;
    private int activeSubscriptionCount;

    /** Sum of every active subscription's monthly-normalised price. */
    private BigDecimal totalMonthlySpend;
    private BigDecimal totalYearlySpend;

    private int totalUsageMinutes;

    /** Subscriptions with zero recorded usage in the period. */
    private int unusedCount;
    /** Subscriptions with no snapshot at all -- unknown rather than unused. */
    private int noDataCount;

    /** Certain savings: the combined price of everything recommended for cancellation. */
    private BigDecimal potentialMonthlySavings;
    private BigDecimal potentialYearlySavings;
    /** Share of monthly spend that the CANCEL verdicts represent, 0-100. */
    private BigDecimal wastedSpendPercent;

    /** Count of subscriptions per recommendation type, e.g. {KEEP=2, CANCEL=1}. */
    private Map<String, Integer> recommendationCounts;

    private String bestValueProvider;
    private BigDecimal bestValueScore;
    private String worstValueProvider;
    private BigDecimal worstValueScore;
}
