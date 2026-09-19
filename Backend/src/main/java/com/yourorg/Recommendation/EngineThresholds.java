package com.yourorg.Recommendation;

import java.math.BigDecimal;

/**
 * Tunable decision boundaries for {@link RecommendationEngine}.
 *
 * <p><b>Calibration caveat:</b> {@code maxAcceptableCostPerHour} is an amount of
 * money, so it is currency-specific. The defaults below are calibrated for INR,
 * which is what the seed data and the Android client use: at 200 INR/hour a
 * 199 INR video subscription needs roughly one hour of viewing a month to stop
 * looking wasteful. A deployment using another currency must override this value
 * or the scores will be meaningless.
 *
 * @param maxAcceptableCostPerHour cost per hour at which the value score reaches 0
 * @param minMonthlyHoursForValue  usage below which a paid subscription counts as
 *                                 effectively unused, regardless of price
 * @param keepScore                score at or above which the verdict is KEEP
 * @param considerScore            score at or above which the verdict is CONSIDER
 */
public record EngineThresholds(
        BigDecimal maxAcceptableCostPerHour,
        BigDecimal minMonthlyHoursForValue,
        BigDecimal keepScore,
        BigDecimal considerScore
) {

    public static EngineThresholds defaults() {
        return new EngineThresholds(
                new BigDecimal("200"),
                new BigDecimal("1.0"),
                new BigDecimal("70"),
                new BigDecimal("40")
        );
    }
}
