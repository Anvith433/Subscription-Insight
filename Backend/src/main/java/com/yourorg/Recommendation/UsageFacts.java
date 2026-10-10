package com.yourorg.Recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Everything the {@link RecommendationEngine} is allowed to reason about for a
 * single subscription. Assembled by {@link RecommendationService} from the
 * database; the engine itself never touches persistence, which keeps the
 * decision rules unit-testable without a running MySQL.
 *
 * @param providerName          display name, used in the explanation text
 * @param currency              ISO code, used in the explanation text
 * @param monthlyPrice          price normalised to a monthly amount (never null)
 * @param usageMinutes          minutes used during {@code period}; {@code null}
 *                              means no snapshot exists, which is different from
 *                              a recorded zero
 * @param priorUsageMinutes     minutes used in the month before {@code period}, scaled
 *                              to the number of days {@code usageMinutes} covers so the
 *                              trend compares daily rates; {@code null} when there is
 *                              no history or that month was not seen in full
 * @param lastUsedAt            timestamp of the last observed use, may be null
 * @param startDate             when the subscription began
 * @param period                the month being assessed
 * @param portfolioMedianPrice  median monthly price across the user's active
 *                              subscriptions; used to judge whether a cheaper
 *                              tier is a plausible suggestion
 * @param today                 injected rather than read from the clock so tests
 *                              are deterministic
 * @param observedDays          days of {@code period} the device scan actually
 *                              covered. {@code null} means the scan window was not
 *                              recorded, which is read as a complete month -- see
 *                              the note below
 * @param monthDaysElapsed      days elapsed in {@code period} when the scan was
 *                              taken; the denominator for {@code observedDays}.
 *                              {@code null} for the same reason
 */
public record UsageFacts(
        String providerName,
        String currency,
        BigDecimal monthlyPrice,
        Integer usageMinutes,
        Integer priorUsageMinutes,
        Instant lastUsedAt,
        LocalDate startDate,
        YearMonth period,
        BigDecimal portfolioMedianPrice,
        LocalDate today,
        Integer observedDays,
        Integer monthDaysElapsed
) {

    /**
     * Facts with no scan-window information, i.e. "assume the whole month".
     *
     * <p>Kept because that is the honest reading of a row written before the window
     * was recorded at all: the snapshot exists, but nothing says how much of the
     * month it looked at. Treating those rows as partial would flip every one of them
     * to a provisional verdict; treating them as complete preserves exactly the
     * behaviour they were written under.
     */
    public UsageFacts(
            String providerName,
            String currency,
            BigDecimal monthlyPrice,
            Integer usageMinutes,
            Integer priorUsageMinutes,
            Instant lastUsedAt,
            LocalDate startDate,
            YearMonth period,
            BigDecimal portfolioMedianPrice,
            LocalDate today) {
        this(providerName, currency, monthlyPrice, usageMinutes, priorUsageMinutes,
                lastUsedAt, startDate, period, portfolioMedianPrice, today, null, null);
    }

    /**
     * Whether the stored usage figure is a lower bound rather than the month's total,
     * because the scan covered only part of the month so far.
     */
    public boolean isPartialScan() {
        return observedDays != null && monthDaysElapsed != null
                && monthDaysElapsed > 0 && observedDays < monthDaysElapsed;
    }

    /**
     * Fraction of the elapsed month the scan covered, in (0, 1]. Returns 1 when no
     * window was recorded, matching {@link #isPartialScan()}'s assumption.
     */
    public double scanCoverage() {
        if (!isPartialScan()) {
            return 1.0;
        }
        return (double) observedDays / monthDaysElapsed;
    }
}
