package com.yourorg.UserSnapShot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the scan-window arithmetic.
 *
 * <p>Dates are fixed rather than taken from the clock, and the assertions name the
 * weekday they depend on, because a test that only passes in some weeks of some
 * months is worse than no test. March 2026 is used throughout: the 1st is a Sunday,
 * which makes it a useful worst case for the Monday-anchored week.
 */
class UsageWindowTest {

    /** Sunday. The first day of the month is not a Monday, which is the awkward case. */
    private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
    /** Wednesday, mid-month: its Monday (the 16th) is comfortably inside the month. */
    private static final LocalDate MAR_18 = LocalDate.of(2026, 3, 18);
    /** Friday the 20th, used for the plain day-count cases. */
    private static final LocalDate MAR_20 = LocalDate.of(2026, 3, 20);

    @Test
    @DisplayName("The fixtures really are the weekdays the other tests assume")
    void fixtureWeekdaysAreWhatTheyClaim() {
        assertEquals(DayOfWeek.SUNDAY, MAR_1.getDayOfWeek());
        assertEquals(DayOfWeek.WEDNESDAY, MAR_18.getDayOfWeek());
    }

    @Test
    @DisplayName("A month scan on the 20th covers all 20 elapsed days, so it is complete")
    void monthCoversTheWholeElapsedMonth() {
        assertEquals(LocalDate.of(2026, 3, 1), UsageWindow.MONTH.startWithin(MAR_20));
        assertEquals(20, UsageWindow.MONTH.observedDaysWithin(MAR_20));
        // Equal to the denominator is what "complete" means to the engine.
        assertEquals(20, UsageWindow.monthDaysElapsed(MAR_20));
    }

    @Test
    @DisplayName("A day scan covers exactly one day, whatever the date")
    void dayCoversOneDay() {
        assertEquals(MAR_20, UsageWindow.DAY.startWithin(MAR_20));
        assertEquals(1, UsageWindow.DAY.observedDaysWithin(MAR_20));
        assertEquals(1, UsageWindow.DAY.observedDaysWithin(MAR_1));
    }

    @Test
    @DisplayName("A week scan on a mid-month Wednesday covers Monday to Wednesday")
    void weekCoversMondayToToday() {
        assertEquals(LocalDate.of(2026, 3, 16), UsageWindow.WEEK.startWithin(MAR_18));
        assertEquals(3, UsageWindow.WEEK.observedDaysWithin(MAR_18));
        // 3 of 18 days: firmly partial, which is the whole point of recording it.
        assertTrue(UsageWindow.WEEK.observedDaysWithin(MAR_18)
                < UsageWindow.monthDaysElapsed(MAR_18));
    }

    @Test
    @DisplayName("A week reaching into the previous month is clamped to the 1st, not counted negative")
    void weekStraddlingTheMonthBoundaryIsClamped() {
        // 1 March is a Sunday, so its Monday is 23 February. Left unclamped the day
        // count would run to 7 and describe days that belong to the previous month.
        assertEquals(MAR_1, UsageWindow.WEEK.startWithin(MAR_1));
        assertEquals(1, UsageWindow.WEEK.observedDaysWithin(MAR_1));

        // Monday 2 March starts a fresh week, so a week scan that day covers only the
        // 2nd -- it does not reach back to Sunday the 1st, which belongs to the
        // previous week. 1 of 2 days: correctly partial, not "the whole month so far".
        LocalDate mar2 = LocalDate.of(2026, 3, 2);
        assertEquals(DayOfWeek.MONDAY, mar2.getDayOfWeek());
        assertEquals(1, UsageWindow.WEEK.observedDaysWithin(mar2));
        assertEquals(2, UsageWindow.monthDaysElapsed(mar2));

        // Where the clamp does make a week complete: 1 April is a Wednesday, so
        // Thursday the 2nd sits in a week starting Monday 30 March. Clamped to the 1st,
        // that week covers both elapsed days of April, and the scan is rightly read as
        // complete -- it over-counts (it includes late-March minutes) rather than
        // missing anything, and over-counting can only bias toward KEEP.
        LocalDate apr2 = LocalDate.of(2026, 4, 2);
        assertEquals(DayOfWeek.THURSDAY, apr2.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 4, 1), UsageWindow.WEEK.startWithin(apr2));
        assertEquals(UsageWindow.monthDaysElapsed(apr2), UsageWindow.WEEK.observedDaysWithin(apr2));
    }

    @Test
    @DisplayName("Coverage is always at least one day and never more than the month has elapsed")
    void coverageStaysWithinTheElapsedMonth() {
        // Two months with different starting weekdays: March 2026 begins on a Sunday
        // (so its first week straddles February) and April on a Wednesday. Between them
        // they exercise both the clamped and the unclamped path on every day.
        for (int month : new int[] {3, 4}) {
            LocalDate first = LocalDate.of(2026, month, 1);
            for (int day = 1; day <= first.lengthOfMonth(); day++) {
                LocalDate today = LocalDate.of(2026, month, day);
                int elapsed = UsageWindow.monthDaysElapsed(today);
                for (UsageWindow window : UsageWindow.values()) {
                    int observed = window.observedDaysWithin(today);
                    assertTrue(observed >= 1 && observed <= elapsed,
                            window + " on " + today + " reported " + observed + " of " + elapsed);
                    assertTrue(!window.startWithin(today).isBefore(first),
                            window + " on " + today + " started before the 1st");
                }
            }
        }
    }

    @Test
    @DisplayName("The client's period strings map to windows; anything unknown means a full month")
    void clientStringsMapOntoWindows() {
        assertEquals(UsageWindow.DAY, UsageWindow.fromClient("TODAY"));
        assertEquals(UsageWindow.WEEK, UsageWindow.fromClient("WEEK"));
        assertEquals(UsageWindow.MONTH, UsageWindow.fromClient("MONTH"));

        // Case and surrounding whitespace are the client's business, not ours.
        assertEquals(UsageWindow.DAY, UsageWindow.fromClient("today"));
        assertEquals(UsageWindow.MONTH, UsageWindow.fromClient("  Month  "));

        // Absent or unrecognised falls back to the reading the server used before it
        // recorded windows at all, so an older client is not silently downgraded to a
        // provisional verdict.
        assertEquals(UsageWindow.MONTH, UsageWindow.fromClient(null));
        assertEquals(UsageWindow.MONTH, UsageWindow.fromClient(""));
        assertEquals(UsageWindow.MONTH, UsageWindow.fromClient("   "));
        assertEquals(UsageWindow.MONTH, UsageWindow.fromClient("garbage"));
        assertEquals(UsageWindow.MONTH, UsageWindow.fromClient("LAST_90_DAYS"));
    }

    @Test
    @DisplayName("A stored window name round-trips, so re-reading a row cannot change its meaning")
    void enumNamesRoundTrip() {
        for (UsageWindow window : UsageWindow.values()) {
            assertEquals(window, UsageWindow.fromClient(window.name()));
        }
    }
}
