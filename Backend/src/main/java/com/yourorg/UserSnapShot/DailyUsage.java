package com.yourorg.UserSnapShot;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The rules for per-day usage: what counts as a believable report, and what a month
 * looks like once it is assembled from its days.
 *
 * <p>Kept free of Spring and JPA, like {@link UsageWindow}, so the arithmetic can be
 * unit-tested without a database.
 */
public final class DailyUsage {

    /** A calendar day has 1440 minutes; no app can be used for more. */
    public static final int MINUTES_PER_DAY = 24 * 60;

    private DailyUsage() {
    }

    /**
     * Parses one app's daily figures, or returns null if any of them cannot be a
     * measurement: a malformed date, a date more than a day after {@code today},
     * minutes outside 0..1440, or the same date listed twice.
     *
     * <p>One day ahead is allowed because dates are the phone's calendar days: a
     * phone a few hours ahead of the server is already on tomorrow. Such a day is
     * accepted here but not stored by the caller until the server reaches it -- the
     * next sync sends it again.
     *
     * <p>All or nothing on purpose. A report with one impossible day came from a
     * client that is wrong about something, and storing the rest of it would mix
     * figures of unknown quality into a month that is otherwise measured.
     *
     * @return the figures keyed and ordered by date, never empty when non-null
     */
    public static Map<LocalDate, Integer> validate(List<DayUsageDTO> days, LocalDate today) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        Map<LocalDate, Integer> parsed = new TreeMap<>();
        for (DayUsageDTO day : days) {
            if (day == null || day.getDate() == null) {
                return null;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(day.getDate().trim());
            } catch (DateTimeParseException e) {
                return null;
            }
            if (date.isAfter(today.plusDays(1)) || day.getMinutes() < 0 || day.getMinutes() > MINUTES_PER_DAY) {
                return null;
            }
            if (parsed.put(date, day.getMinutes()) != null) {
                return null;
            }
        }
        return parsed;
    }

    /**
     * Whether a single-total report (an older client's TODAY/WEEK/MONTH scan) is
     * possible at all: it cannot contain more minutes than the days it covered.
     */
    public static boolean fitsWithin(int minutes, int observedDays) {
        return minutes >= 0 && (long) minutes <= (long) observedDays * MINUTES_PER_DAY;
    }

    /**
     * The previous month's minutes put on the same footing as the current figure, for
     * a month-over-month trend: scaled to the number of days the current figure covers,
     * so ten days of this month are compared with ten days' worth of last month rather
     * than with all thirty -- which would read as a steep fall every month until the
     * last week.
     *
     * <p>Null, meaning "no fair comparison", when the previous month was not seen in
     * full: its gaps would show up as a change in behaviour. That is the common case
     * for the first month a subscription is synced, which otherwise reported things
     * like "up 925%" against a single recorded day.
     *
     * @param priorObservedDays days of the previous month its figure covers
     * @param priorDaysElapsed  days of the previous month that had passed when it was
     *                          recorded; the figure is complete when the two are equal
     * @param currentDays       days the current figure covers
     */
    public static Integer comparablePrior(int priorMinutes, int priorObservedDays,
                                          int priorDaysElapsed, int currentDays) {
        if (priorObservedDays <= 0 || priorObservedDays < priorDaysElapsed || currentDays <= 0) {
            return null;
        }
        return (int) Math.round((double) priorMinutes * currentDays / priorObservedDays);
    }

    /**
     * What a month's snapshot should say, given every day recorded for it so far.
     *
     * <p>{@code observedDays} counts days that have a row, zeros included, so gaps
     * left by a missed sync make the month read as partial -- a lower bound the engine
     * will not CANCEL on -- rather than as a complete month with low usage.
     *
     * <p>{@code monthDaysElapsed} is the day of the month for the current month, and
     * the month's full length for a past one: a finished month is only complete once
     * every one of its days has been seen.
     *
     * @param recorded the recorded days; ones outside {@code month} or after
     *                 {@code today} are ignored
     */
    public static MonthFigures rollUp(YearMonth month, LocalDate today, Map<LocalDate, Integer> recorded) {
        YearMonth current = YearMonth.from(today);
        if (month.isAfter(current)) {
            throw new IllegalArgumentException(month + " has not started yet (today is " + today + ")");
        }
        int minutes = 0;
        int observed = 0;
        for (Map.Entry<LocalDate, Integer> day : recorded.entrySet()) {
            LocalDate date = day.getKey();
            if (!YearMonth.from(date).equals(month) || date.isAfter(today)) {
                continue;
            }
            minutes += day.getValue();
            observed++;
        }
        int elapsed = month.equals(current) ? today.getDayOfMonth() : month.lengthOfMonth();
        return new MonthFigures(minutes, observed, elapsed);
    }

    /**
     * @param minutes          total minutes across the recorded days
     * @param observedDays     how many of the month's days have a recorded figure
     * @param monthDaysElapsed how many of the month's days have happened
     */
    public record MonthFigures(int minutes, int observedDays, int monthDaysElapsed) {

        public boolean isComplete() {
            return observedDays >= monthDaysElapsed;
        }
    }
}
