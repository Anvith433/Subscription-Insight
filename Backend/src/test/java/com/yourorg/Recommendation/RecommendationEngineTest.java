package com.yourorg.Recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the recommendation rules.
 *
 * <p>These run without MySQL, Spring, or any test container, because the engine is a
 * pure function. That is the point of keeping persistence out of it: the previous
 * codebase had exactly one test -- a {@code @SpringBootTest} that only checked the
 * context loads, and could not run at all without a live database.
 */
class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine(EngineThresholds.defaults());

    private static final YearMonth PERIOD = YearMonth.of(2026, 3);
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 28);
    /** Well before the assessed month, so the partial-month rule never interferes. */
    private static final LocalDate LONG_AGO = LocalDate.of(2025, 1, 1);

    private UsageFacts facts(BigDecimal price, Integer minutes) {
        return facts(price, minutes, null, null);
    }

    private UsageFacts facts(BigDecimal price, Integer minutes, Integer priorMinutes, BigDecimal median) {
        return new UsageFacts("Netflix", "INR", price, minutes, priorMinutes,
                null, LONG_AGO, PERIOD, median, TODAY);
    }

    /**
     * Facts whose usage figure came from a scan covering only part of the month, which
     * is what the app produces when the user picks "Today" or "This Week".
     */
    private UsageFacts partialFacts(BigDecimal price, Integer minutes,
                                    int observedDays, int monthDaysElapsed) {
        return new UsageFacts("Netflix", "INR", price, minutes, null,
                null, LONG_AGO, PERIOD, null, TODAY, observedDays, monthDaysElapsed);
    }

    @Test
    @DisplayName("No snapshot yields CONSIDER, never CANCEL: absent data is not zero usage")
    void noSnapshotIsNotZeroUsage() {
        Verdict v = engine.assess(facts(new BigDecimal("649.00"), null));

        assertEquals(Type.CONSIDER, v.type());
        assertEquals(new BigDecimal("0.20"), v.confidence());
        // No usage figure means no cost-per-hour and no defensible saving claim.
        assertNull(v.costPerHour());
        assertEquals(BigDecimal.ZERO, v.estimatedMonthlySavings());
        assertTrue(v.reason().contains("No usage has been synced"), v.reason());
    }

    @Test
    @DisplayName("Paid but never used yields CANCEL, and the saving is the full price")
    void unusedSubscriptionIsCancelled() {
        Verdict v = engine.assess(facts(new BigDecimal("649.00"), 0));

        assertEquals(Type.CANCEL, v.type());
        assertEquals(0, v.estimatedMonthlySavings().compareTo(new BigDecimal("649.00")));
        assertTrue(v.confidence().compareTo(new BigDecimal("0.90")) >= 0);
        assertTrue(v.reason().contains("Not used at all"), v.reason());
        // Both the monthly and the annual figure, so the cost is legible.
        assertTrue(v.reason().contains("INR 649.00"), v.reason());
        assertTrue(v.reason().contains("INR 7788.00"), v.reason());
    }

    @Test
    @DisplayName("Barely used (under an hour) yields CANCEL")
    void barelyUsedSubscriptionIsCancelled() {
        Verdict v = engine.assess(facts(new BigDecimal("649.00"), 20));

        assertEquals(Type.CANCEL, v.type());
        assertEquals(0, v.estimatedMonthlySavings().compareTo(new BigDecimal("649.00")));
        // 649 / (20/60) h = 1947.00 per hour.
        assertEquals(0, v.costPerHour().compareTo(new BigDecimal("1947.00")));
        // Far past the acceptable ceiling, so the score is floored at zero.
        assertEquals(0, v.valueScore().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Heavy use yields KEEP and claims no saving")
    void wellUsedSubscriptionIsKept() {
        // 649 over 40 h = 16.23/h -> score 100 * (1 - 16.23/200) = 91.9
        Verdict v = engine.assess(facts(new BigDecimal("649.00"), 2400));

        assertEquals(Type.KEEP, v.type());
        assertEquals(0, v.costPerHour().compareTo(new BigDecimal("16.23")));
        assertTrue(v.valueScore().compareTo(new BigDecimal("70")) >= 0, "score was " + v.valueScore());
        assertEquals(BigDecimal.ZERO, v.estimatedMonthlySavings());
    }

    @Test
    @DisplayName("A free plan is always KEEP: there is no cost to justify")
    void freePlanIsKept() {
        Verdict v = engine.assess(facts(BigDecimal.ZERO, 5));

        assertEquals(Type.KEEP, v.type());
        assertEquals(0, v.valueScore().compareTo(new BigDecimal("100")));
        assertEquals(BigDecimal.ZERO, v.estimatedMonthlySavings());
        assertTrue(v.reason().contains("free plan"), v.reason());
    }

    @Test
    @DisplayName("A subscription started mid-period yields CONSIDER, not a verdict on partial data")
    void partialMonthDefersJudgement() {
        UsageFacts f = new UsageFacts("Netflix", "INR", new BigDecimal("649.00"), 30, null,
                null, LocalDate.of(2026, 3, 20), PERIOD, null, TODAY);

        Verdict v = engine.assess(f);

        // 30 minutes would otherwise be a confident CANCEL.
        assertEquals(Type.CONSIDER, v.type());
        assertEquals(BigDecimal.ZERO, v.estimatedMonthlySavings());
        assertTrue(v.reason().contains("partial month"), v.reason());
    }

    @Test
    @DisplayName("Poor value above the portfolio median yields DOWNGRADE with no invented saving")
    void poorValueAboveMedianIsDowngraded() {
        // 1500 over 5 h = 300/h, above the 200 ceiling -> score 0.
        Verdict v = engine.assess(facts(new BigDecimal("1500.00"), 300, null, new BigDecimal("400.00")));

        assertEquals(Type.DOWNGRADE, v.type());
        // A downgrade saving depends on a cheaper tier's price, which is unknown.
        assertEquals(BigDecimal.ZERO, v.estimatedMonthlySavings());
        assertTrue(v.reason().contains("cheaper tier"), v.reason());
    }

    @Test
    @DisplayName("Poor value on an already-cheap plan yields CONSIDER, not DOWNGRADE")
    void poorValueBelowMedianIsNotDowngraded() {
        // Same poor value per hour, but this is one of the user's cheaper subscriptions.
        Verdict v = engine.assess(facts(new BigDecimal("1500.00"), 300, null, new BigDecimal("2000.00")));

        assertEquals(Type.CONSIDER, v.type());
        assertTrue(v.reason().contains("no lower tier"), v.reason());
    }

    @Test
    @DisplayName("Falling usage raises confidence in CANCEL; rising usage lowers it")
    void trendAdjustsConfidenceWithoutChangingTheVerdict() {
        Verdict falling = engine.assess(facts(new BigDecimal("649.00"), 20, 600, null));
        Verdict rising = engine.assess(facts(new BigDecimal("649.00"), 20, 5, null));

        assertEquals(Type.CANCEL, falling.type());
        assertEquals(Type.CANCEL, rising.type());
        assertTrue(falling.confidence().compareTo(rising.confidence()) > 0,
                "falling=" + falling.confidence() + " rising=" + rising.confidence());
        assertTrue(falling.reason().contains("down"), falling.reason());
        assertTrue(rising.reason().contains("up"), rising.reason());
    }

    @Test
    @DisplayName("UPGRADE is never emitted, because no plan catalogue exists to justify it")
    void upgradeIsNeverEmitted() {
        int[] minuteSamples = {0, 1, 30, 59, 60, 120, 600, 2400, 10000};
        BigDecimal[] priceSamples = {BigDecimal.ZERO, new BigDecimal("99"), new BigDecimal("649"),
                new BigDecimal("5000")};

        for (int minutes : minuteSamples) {
            for (BigDecimal price : priceSamples) {
                Verdict v = engine.assess(facts(price, minutes, null, new BigDecimal("500")));
                assertNotNull(v.type());
                assertTrue(v.type() != Type.UPGRADE,
                        "UPGRADE returned for " + price + " / " + minutes + " min");
            }
        }
    }

    @Test
    @DisplayName("A saving is only ever claimed for CANCEL")
    void savingsAreOnlyClaimedForCancel() {
        int[] minuteSamples = {0, 30, 120, 600, 2400};
        BigDecimal[] priceSamples = {BigDecimal.ZERO, new BigDecimal("199"), new BigDecimal("1500")};

        for (int minutes : minuteSamples) {
            for (BigDecimal price : priceSamples) {
                Verdict v = engine.assess(facts(price, minutes, null, new BigDecimal("500")));
                if (v.type() != Type.CANCEL) {
                    assertEquals(0, v.estimatedMonthlySavings().compareTo(BigDecimal.ZERO),
                            v.type() + " claimed a saving for " + price + " / " + minutes + " min");
                } else {
                    assertEquals(0, v.estimatedMonthlySavings().compareTo(price),
                            "CANCEL saving should equal the price");
                }
            }
        }
    }

    @Test
    @DisplayName("Confidence always stays within 0.05 and 0.99")
    void confidenceStaysInRange() {
        int[] minuteSamples = {0, 5, 60, 300, 3000};
        Integer[] priorSamples = {null, 0, 1, 100, 5000};

        for (int minutes : minuteSamples) {
            for (Integer prior : priorSamples) {
                Verdict v = engine.assess(facts(new BigDecimal("649.00"), minutes, prior, null));
                assertTrue(v.confidence().compareTo(new BigDecimal("0.05")) >= 0
                                && v.confidence().compareTo(new BigDecimal("0.99")) <= 0,
                        "confidence out of range: " + v.confidence());
            }
        }
    }

    @Test
    @DisplayName("A last-used timestamp in the future never reports negative days")
    void lastUsedTodayDoesNotReportNegativeDays() {
        UsageFacts f = new UsageFacts("Netflix", "INR", new BigDecimal("649.00"), 0, null,
                Instant.now(), LONG_AGO, PERIOD, null, LocalDate.now());

        Verdict v = engine.assess(f);

        assertEquals(Type.CANCEL, v.type());
        assertTrue(v.reason().contains("0 days ago"), v.reason());
    }

    @Test
    @DisplayName("Every reason fits the recommendations.reason column")
    void reasonFitsTheColumn() {
        String longName = "A".repeat(300);
        UsageFacts f = new UsageFacts(longName, "INR", new BigDecimal("1500.00"), 300, 900,
                Instant.now(), LONG_AGO, PERIOD, new BigDecimal("400.00"), TODAY);

        Verdict v = engine.assess(f);

        assertTrue(v.reason().length() <= 500, "reason was " + v.reason().length() + " chars");
    }

    // ---------------------------------------------------------------------------
    // Partial scans. The device reports one total for whatever span it was asked
    // about; a one-day scan proves usage was *at least* that much and says nothing
    // about the other days. Before the scan window was recorded, every scan was filed
    // as a whole month, so a "Today" sync early in the month produced a confident
    // CANCEL from a single day of data.
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Zero minutes on a one-day scan yields CONSIDER: 'nothing today' is not 'nothing this month'")
    void partialScanWithZeroMinutesDoesNotCancel() {
        Verdict v = engine.assess(partialFacts(new BigDecimal("649.00"), 0, 1, 28));

        // Identical facts over a complete month are a 0.95-confidence CANCEL.
        assertEquals(Type.CONSIDER, v.type());
        assertEquals(0, v.estimatedMonthlySavings().compareTo(BigDecimal.ZERO));
        assertTrue(v.reason().contains("partial scan"), v.reason());
        assertTrue(v.reason().contains("1 of 28 days"), v.reason());
        // Below the 0.65 a full month's data earns, so it cannot outrank a real verdict.
        assertTrue(v.confidence().compareTo(new BigDecimal("0.65")) < 0, "confidence " + v.confidence());
        assertTrue(v.reason().length() <= 500, "reason was " + v.reason().length() + " chars");
    }

    @Test
    @DisplayName("Barely-used on a one-day scan yields CONSIDER at low confidence, not CANCEL")
    void partialScanWithLowUsageDoesNotCancel() {
        // 20 min of a 199/month plan is 597/hour -- a 0.85-confidence CANCEL if the
        // month were complete. Over one day it is simply not enough to know.
        Verdict v = engine.assess(partialFacts(new BigDecimal("199.00"), 20, 1, 28));

        assertEquals(Type.CONSIDER, v.type());
        assertTrue(v.confidence().compareTo(new BigDecimal("0.40")) < 0, "confidence " + v.confidence());
        assertTrue(v.reason().contains("at least"), v.reason());
        assertTrue(v.reason().contains("Sync a full month"), v.reason());
        assertEquals(0, v.estimatedMonthlySavings().compareTo(BigDecimal.ZERO));
        // The metrics are still reported -- they are real, just measured over less time.
        assertEquals(0, v.costPerHour().compareTo(new BigDecimal("597.00")));
    }

    @Test
    @DisplayName("Usage that already justifies the price yields KEEP even on a partial scan")
    void partialScanWithHeavyUsageIsKept() {
        // 1400 min over 7 of 28 days: 23.3 h at 27.81/h, score 86. The unscanned days
        // can only add usage, so acting on the lower bound is safe.
        Verdict v = engine.assess(partialFacts(new BigDecimal("649.00"), 1400, 7, 28));

        assertEquals(Type.KEEP, v.type());
        assertTrue(v.reason().contains("at least"), v.reason());
        // Cost per hour computed from a lower bound on usage is an upper bound on cost.
        assertTrue(v.reason().contains("at most"), v.reason());
        assertTrue(v.valueScore().compareTo(new BigDecimal("70")) >= 0, "score was " + v.valueScore());
        assertEquals(0, v.estimatedMonthlySavings().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("A scan covering the whole elapsed month is not partial, so CANCEL still applies")
    void completeScanStillCancels() {
        Verdict v = engine.assess(partialFacts(new BigDecimal("649.00"), 0, 28, 28));

        assertEquals(Type.CANCEL, v.type());
        assertEquals(0, v.estimatedMonthlySavings().compareTo(new BigDecimal("649.00")));
        assertTrue(v.reason().contains("Not used at all"), v.reason());
    }

    @Test
    @DisplayName("A row with no window recorded is read as a complete month, preserving old behaviour")
    void absentWindowIsTreatedAsComplete() {
        // Rows written before the window was recorded -- the seed data, and anything
        // synced by an older client -- have null coverage. Reading those as partial
        // would flip every existing verdict to a provisional CONSIDER.
        Verdict v = engine.assess(facts(new BigDecimal("649.00"), 0));

        assertEquals(Type.CANCEL, v.type());
        assertEquals(0, v.estimatedMonthlySavings().compareTo(new BigDecimal("649.00")));
    }

    @Test
    @DisplayName("Confidence in a provisional verdict rises with the share of the month scanned")
    void partialConfidenceRisesWithCoverage() {
        Verdict oneDay = engine.assess(partialFacts(new BigDecimal("649.00"), 0, 1, 20));
        Verdict mostOfIt = engine.assess(partialFacts(new BigDecimal("649.00"), 0, 15, 20));

        assertEquals(Type.CONSIDER, oneDay.type());
        assertEquals(Type.CONSIDER, mostOfIt.type());
        assertTrue(mostOfIt.confidence().compareTo(oneDay.confidence()) > 0,
                "1/20=" + oneDay.confidence() + " 15/20=" + mostOfIt.confidence());
        // Never below the "no data at all" floor, never at full-data confidence.
        assertTrue(oneDay.confidence().compareTo(new BigDecimal("0.20")) >= 0, "" + oneDay.confidence());
        assertTrue(mostOfIt.confidence().compareTo(new BigDecimal("0.65")) < 0, "" + mostOfIt.confidence());
    }

    @Test
    @DisplayName("A partial scan never yields CANCEL and never claims a saving, at any price or usage")
    void partialScanNeverCancels() {
        int[] minuteSamples = {0, 1, 20, 59, 60, 300, 2400};
        BigDecimal[] priceSamples = {new BigDecimal("99"), new BigDecimal("649"), new BigDecimal("5000")};

        for (int minutes : minuteSamples) {
            for (BigDecimal price : priceSamples) {
                Verdict v = engine.assess(partialFacts(price, minutes, 2, 28));
                assertTrue(v.type() != Type.CANCEL,
                        "CANCEL from a 2-of-28-day scan: " + price + " / " + minutes + " min");
                assertEquals(0, v.estimatedMonthlySavings().compareTo(BigDecimal.ZERO),
                        v.type() + " claimed a saving from a partial scan");
                assertTrue(v.confidence().compareTo(new BigDecimal("0.05")) >= 0
                                && v.confidence().compareTo(new BigDecimal("0.99")) <= 0,
                        "confidence out of range: " + v.confidence());
                assertTrue(v.reason().length() <= 500,
                        "reason was " + v.reason().length() + " chars");
            }
        }
    }

    @Test
    @DisplayName("A free plan and an absent snapshot still short-circuit ahead of the partial-scan rule")
    void earlierCasesKeepPriorityOverPartialScans() {
        // Nothing to justify, so the span scanned is irrelevant.
        Verdict free = engine.assess(partialFacts(BigDecimal.ZERO, 0, 1, 28));
        assertEquals(Type.KEEP, free.type());
        assertTrue(free.reason().contains("free plan"), free.reason());

        // No snapshot is a different statement from a short one, and keeps its own
        // wording so the user knows to sync rather than to sync for longer.
        Verdict noData = engine.assess(partialFacts(new BigDecimal("649.00"), null, 1, 28));
        assertEquals(Type.CONSIDER, noData.type());
        assertTrue(noData.reason().contains("No usage has been synced"), noData.reason());

        // "Started on the 20th" is more specific than "scanned one day", so it wins.
        UsageFacts startedMidMonth = new UsageFacts("Netflix", "INR", new BigDecimal("649.00"),
                30, null, null, LocalDate.of(2026, 3, 20), PERIOD, null, TODAY, 1, 28);
        Verdict fresh = engine.assess(startedMidMonth);
        assertEquals(Type.CONSIDER, fresh.type());
        assertTrue(fresh.reason().contains("partial month"), fresh.reason());
    }

    @Test
    @DisplayName("UPGRADE is never emitted from a partial scan either")
    void partialScanNeverEmitsUpgrade() {
        for (int observed = 1; observed <= 27; observed += 13) {
            for (int minutes : new int[] {0, 30, 600, 5000}) {
                Verdict v = engine.assess(partialFacts(new BigDecimal("649"), minutes, observed, 28));
                assertNotNull(v.type());
                assertTrue(v.type() != Type.UPGRADE,
                        "UPGRADE returned for " + minutes + " min over " + observed + " days");
            }
        }
    }
}
