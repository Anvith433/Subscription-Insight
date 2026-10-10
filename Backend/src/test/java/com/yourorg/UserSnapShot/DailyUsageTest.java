package com.yourorg.UserSnapShot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for per-day usage: which reports are believable, and how a month is put
 * together from its days. Dates are fixed rather than read from the clock.
 */
class DailyUsageTest {

    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private static DayUsageDTO day(String date, int minutes) {
        DayUsageDTO d = new DayUsageDTO();
        d.setDate(date);
        d.setMinutes(minutes);
        return d;
    }

    // ------------------------------------------------------------------ validate

    @Test
    @DisplayName("A well-formed report parses, ordered by date, zeros kept")
    void validReportParses() {
        Map<LocalDate, Integer> parsed = DailyUsage.validate(
                List.of(day("2026-10-10", 35), day("2026-09-30", 0), day("2026-10-01", 1440)), OCT_10);

        assertNotNull(parsed);
        assertEquals(List.of(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), OCT_10),
                new ArrayList<>(parsed.keySet()));
        assertEquals(0, parsed.get(LocalDate.of(2026, 9, 30)));
    }

    @Test
    @DisplayName("Two days ahead is rejected; one day ahead is a phone in a later time zone")
    void futureDateRejected() {
        assertNull(DailyUsage.validate(List.of(day("2026-10-10", 5), day("2026-10-12", 5)), OCT_10));
        assertNotNull(DailyUsage.validate(List.of(day("2026-10-10", 5), day("2026-10-11", 5)), OCT_10));
    }

    @Test
    @DisplayName("More than 1440 minutes in a day, or a negative figure, is rejected")
    void impossibleMinutesRejected() {
        assertNull(DailyUsage.validate(List.of(day("2026-10-09", 1441)), OCT_10));
        assertNull(DailyUsage.validate(List.of(day("2026-10-09", -1)), OCT_10));
    }

    @Test
    @DisplayName("A malformed, missing or repeated date rejects the whole report")
    void malformedReportRejected() {
        assertNull(DailyUsage.validate(List.of(day("10/09/2026", 5)), OCT_10));
        assertNull(DailyUsage.validate(List.of(day(null, 5)), OCT_10));
        assertNull(DailyUsage.validate(List.of(day("2026-10-09", 5), day("2026-10-09", 6)), OCT_10));
        assertNull(DailyUsage.validate(List.of(), OCT_10));
    }

    // ------------------------------------------------------------------ fitsWithin

    @Test
    @DisplayName("A single total must fit in the days it covered")
    void windowTotalBound() {
        assertTrue(DailyUsage.fitsWithin(0, 1));
        assertTrue(DailyUsage.fitsWithin(1440, 1));
        assertFalse(DailyUsage.fitsWithin(1441, 1));
        assertTrue(DailyUsage.fitsWithin(7 * 1440, 7));
        assertFalse(DailyUsage.fitsWithin(-5, 7));
    }

    // ------------------------------------------------------------------ comparablePrior

    @Test
    @DisplayName("Last month is scaled to this month's covered days, so a steady habit reads as flat")
    void priorScaledToCurrentSpan() {
        // 30 min a day all September; 10 days into October at the same rate.
        assertEquals(300, DailyUsage.comparablePrior(900, 30, 30, 10));
    }

    @Test
    @DisplayName("A previous month with gaps gives no trend rather than an invented one")
    void partialPriorGivesNoTrend() {
        assertNull(DailyUsage.comparablePrior(120, 1, 30, 10));
        assertNull(DailyUsage.comparablePrior(0, 0, 30, 10));
    }

    @Test
    @DisplayName("An older mid-month figure is compared at its own daily rate")
    void legacyMidMonthPrior() {
        // A pre-day-based MONTH scan taken on the 20th: complete as of then.
        assertEquals(500, DailyUsage.comparablePrior(1000, 20, 20, 10));
    }

    // ------------------------------------------------------------------ rollUp

    @Test
    @DisplayName("Every day of the month so far recorded: complete, minutes summed")
    void completeCurrentMonth() {
        Map<LocalDate, Integer> days = new TreeMap<>();
        for (int d = 1; d <= 10; d++) {
            days.put(OCTOBER.atDay(d), 30);
        }
        DailyUsage.MonthFigures f = DailyUsage.rollUp(OCTOBER, OCT_10, days);

        assertEquals(300, f.minutes());
        assertEquals(10, f.observedDays());
        assertEquals(10, f.monthDaysElapsed());
        assertTrue(f.isComplete());
    }

    @Test
    @DisplayName("Days with zero minutes still count as observed: they were scanned")
    void zerosAreObservations() {
        Map<LocalDate, Integer> days = new TreeMap<>();
        for (int d = 1; d <= 10; d++) {
            days.put(OCTOBER.atDay(d), 0);
        }
        DailyUsage.MonthFigures f = DailyUsage.rollUp(OCTOBER, OCT_10, days);

        assertEquals(0, f.minutes());
        assertTrue(f.isComplete(), "a scanned month with no use is a complete month of no use");
    }

    @Test
    @DisplayName("A gap left by a missed sync makes the month partial, not short")
    void gapMakesMonthPartial() {
        Map<LocalDate, Integer> days = new TreeMap<>();
        days.put(OCTOBER.atDay(1), 60);
        days.put(OCTOBER.atDay(2), 60);
        // The 3rd to the 7th were never scanned.
        days.put(OCTOBER.atDay(8), 60);
        days.put(OCTOBER.atDay(9), 60);
        days.put(OCT_10, 60);
        DailyUsage.MonthFigures f = DailyUsage.rollUp(OCTOBER, OCT_10, days);

        assertEquals(300, f.minutes());
        assertEquals(5, f.observedDays());
        assertEquals(10, f.monthDaysElapsed());
        assertFalse(f.isComplete());
    }

    @Test
    @DisplayName("A finished month counts all its days as elapsed, so it needs all of them")
    void pastMonthNeedsEveryDay() {
        Map<LocalDate, Integer> days = new TreeMap<>();
        for (int d = 21; d <= 30; d++) {
            days.put(SEPTEMBER.atDay(d), 45);
        }
        DailyUsage.MonthFigures f = DailyUsage.rollUp(SEPTEMBER, OCT_10, days);

        assertEquals(450, f.minutes());
        assertEquals(10, f.observedDays());
        assertEquals(30, f.monthDaysElapsed());
        assertFalse(f.isComplete());
    }

    @Test
    @DisplayName("Days from another month, or after today, are left out of a month's figures")
    void otherDaysIgnored() {
        Map<LocalDate, Integer> days = new TreeMap<>();
        days.put(SEPTEMBER.atDay(30), 999);
        days.put(OCTOBER.atDay(1), 20);
        days.put(OCTOBER.atDay(11), 999);
        DailyUsage.MonthFigures f = DailyUsage.rollUp(OCTOBER, OCT_10, days);

        assertEquals(20, f.minutes());
        assertEquals(1, f.observedDays());
    }

    @Test
    @DisplayName("A month that has not started cannot be rolled up")
    void futureMonthRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> DailyUsage.rollUp(YearMonth.of(2026, 11), OCT_10, Map.of()));
    }
}
