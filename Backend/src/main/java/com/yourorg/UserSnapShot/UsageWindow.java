package com.yourorg.UserSnapShot;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * How much of the current month a device scan actually looked at.
 *
 * <p>The Android client asks {@code AppsUsageMonitorAPI} for one of three spans and
 * sends which one it used. Before this existed the server ignored that field and
 * filed every scan as the whole month, so a single day's usage became the month's
 * total -- twenty minutes of a 199/month plan read as 597 per hour and produced a
 * confident CANCEL off one day of data.
 *
 * <p>Kept free of Spring and JPA on purpose: the day arithmetic is the part most
 * likely to be wrong, and this way it is unit-testable without a database.
 *
 * <h2>Alignment with the device library</h2>
 * The spans mirror the calendar alignment of {@code UsageUtils} inside
 * {@code AppsUsageMonitorAPI-1.1.aar}, read from its bytecode: {@code DAY} is today,
 * {@code WEEK} anchors on Monday ({@code Calendar.set(DAY_OF_WEEK, MONDAY)}) and
 * {@code MONTH} anchors on the 1st ({@code Calendar.set(DAY_OF_MONTH, 1)}). Because
 * {@code MONTH} is calendar-aligned rather than a rolling 30 days, it lines up
 * exactly with the server's {@code YearMonth} period -- a full-month scan is genuinely
 * complete, not merely close.
 */
public enum UsageWindow {

    /** Today only. */
    DAY,

    /** Monday of the current week through today. */
    WEEK,

    /** The 1st of the current month through today. */
    MONTH;

    /**
     * Maps the client's {@code period} string onto a window.
     *
     * <p>{@code "TODAY"} is what the app sends for a single day; {@code "DAY"} is
     * accepted too so {@code fromClient(w.name())} round-trips a stored value.
     *
     * <p>Anything unrecognised -- including {@code null} and blank -- becomes
     * {@link #MONTH}. That is the behaviour-preserving answer: an older client that
     * sends nothing, or a future one that sends a span this server has not heard of,
     * keeps the pre-existing "assume a full month" reading rather than being
     * silently downgraded to a provisional verdict.
     */
    public static UsageWindow fromClient(String raw) {
        if (raw == null) {
            return MONTH;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "TODAY", "DAY" -> DAY;
            case "WEEK" -> WEEK;
            default -> MONTH;
        };
    }

    /**
     * First date this window covers, clamped to the 1st of {@code today}'s month.
     *
     * <p>The clamp is what makes a Monday-anchored week that reaches back into the
     * previous month still report an honest <i>in-month</i> day count. Such a scan
     * over-counts: the minutes it returns include time spent last month, filed
     * against this month. That inflates usage, which biases the verdict toward KEEP
     * -- the safe direction, since inflated usage can never manufacture a false
     * CANCEL. Left as-is rather than corrected, because the device library reports a
     * single total for the span and gives no way to split it by day.
     *
     * <p>A useful consequence: early in the month the clamp can make a week or day
     * scan cover the entire elapsed month, in which case
     * {@link #observedDaysWithin(LocalDate)} equals {@link #monthDaysElapsed(LocalDate)}
     * and the scan is correctly treated as complete.
     */
    public LocalDate startWithin(LocalDate today) {
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        LocalDate start = switch (this) {
            case DAY -> today;
            // previousOrSame, not the library's Calendar.set(DAY_OF_WEEK, MONDAY):
            // Calendar resolves that against the locale's firstDayOfWeek, so on a
            // Sunday under a Sunday-first locale it can land on tomorrow. Clamping
            // to the most recent Monday can only ever understate coverage, which
            // lowers confidence rather than overstating what was measured.
            case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> firstOfMonth;
        };
        return start.isBefore(firstOfMonth) ? firstOfMonth : start;
    }

    /**
     * Days of {@code today}'s month this window covered, counting both ends. Always
     * between 1 and {@link #monthDaysElapsed(LocalDate)}.
     */
    public int observedDaysWithin(LocalDate today) {
        return (int) ChronoUnit.DAYS.between(startWithin(today), today) + 1;
    }

    /**
     * Days elapsed in {@code today}'s month, counting today. The denominator that
     * {@link #observedDaysWithin(LocalDate)} is measured against -- deliberately
     * "elapsed so far" and not the month's full length, because usage that has not
     * happened yet is not missing data.
     */
    public static int monthDaysElapsed(LocalDate today) {
        return today.getDayOfMonth();
    }
}
