package com.yourorg.Recommendation;

import java.math.BigDecimal;

/**
 * The engine's output for one subscription: a verdict plus the evidence that
 * produced it. Every field that appears in the UI is computed here, so the
 * explanation shown to the user can never drift from the numbers the decision
 * was actually based on.
 *
 * @param type                     the action being recommended
 * @param reason                   human-readable explanation (fits the 500-char column)
 * @param confidence               0.00-1.00; low when the underlying data is thin
 * @param valueScore               0-100 value-for-money score, or null when unknown
 * @param costPerHour              money per hour of use, or null when usage is zero/unknown
 * @param estimatedMonthlySavings  money freed per month by acting on the verdict.
 *                                 Only populated for CANCEL, where the saving is
 *                                 exactly the subscription price. Left at zero for
 *                                 DOWNGRADE because the real figure depends on the
 *                                 provider's cheaper tier, which this system does
 *                                 not know.
 */
public record Verdict(
        Type type,
        String reason,
        BigDecimal confidence,
        BigDecimal valueScore,
        BigDecimal costPerHour,
        BigDecimal estimatedMonthlySavings
) {
}
