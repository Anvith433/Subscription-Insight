package com.example.myjavaapplication;

import java.math.BigDecimal;

/**
 * Account-level totals from GET /api/dashboard/{userId}/summary.
 *
 * <p>A subset of the server's DashboardSummaryDTO. {@code recommendationCounts} and the
 * best/worst-value fields are left out: the per-subscription cards underneath the card
 * already say the same thing per row, and a summary that repeats the detail is noise.
 *
 * <p>Every {@code BigDecimal} here is nullable -- a user with no subscriptions gets a
 * summary with nothing to total -- so read them through a null check, not directly.
 */
public class DashboardSummaryResponse {

    /** Month these figures describe, YYYY-MM. */
    private String period;
    private String currency;

    private int activeSubscriptionCount;

    /** Sum of every active subscription's monthly-normalised price. */
    private BigDecimal totalMonthlySpend;

    /** Combined price of everything recommended for cancellation -- a certain saving. */
    private BigDecimal potentialMonthlySavings;
    private BigDecimal potentialYearlySavings;

    /** Share of monthly spend the CANCEL verdicts represent, 0-100. */
    private BigDecimal wastedSpendPercent;

    /** Subscriptions with zero recorded usage in the period. */
    private int unusedCount;

    public String getPeriod() {
        return period;
    }

    public String getCurrency() {
        return currency;
    }

    public int getActiveSubscriptionCount() {
        return activeSubscriptionCount;
    }

    public BigDecimal getTotalMonthlySpend() {
        return totalMonthlySpend;
    }

    public BigDecimal getPotentialMonthlySavings() {
        return potentialMonthlySavings;
    }

    public BigDecimal getPotentialYearlySavings() {
        return potentialYearlySavings;
    }

    public BigDecimal getWastedSpendPercent() {
        return wastedSpendPercent;
    }

    public int getUnusedCount() {
        return unusedCount;
    }
}
