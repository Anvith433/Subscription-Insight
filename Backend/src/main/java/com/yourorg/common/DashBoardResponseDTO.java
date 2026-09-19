package com.yourorg.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.math.BigDecimal;

/**
 * One row of the dashboard: what the subscription costs, how much it was used, and
 * what the engine concluded.
 *
 * <p>The recommendation fields are populated from the stored Recommendation row, so
 * the explanation the user reads is the one the verdict was computed from. Fields
 * added for explainability (value score, cost per hour, savings, confidence) are
 * additive -- an older client that does not know about them simply ignores them.
 */
@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class DashBoardResponseDTO {
    // From Subscription
    private String providerName;
    private String packageName;
    private BigDecimal price;
    private String currency;
    /** Price normalised to a monthly amount, so yearly plans are comparable. */
    private BigDecimal monthlyCost;

    // From UserSnapShot
    private int usageMinutes;
    /** The month this row describes, YYYY-MM. */
    private String billingPeriod;
    /** False when no snapshot exists for the period: "unknown", not "zero". */
    private boolean hasUsageData;

    // From Recommendation
    private String recommendationType;
    private String reason;
    private BigDecimal confidenceScore;
    /** 0-100 value-for-money score. */
    private BigDecimal valueScore;
    private BigDecimal costPerHour;
    private BigDecimal estimatedMonthlySavings;
}
