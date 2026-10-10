package com.example.myjavaapplication;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Reads, from Android's own usage log, how long each app was used on each day this
 * phone can still fully account for.
 *
 * <p>This replaced {@code AppsUsageMonitorAPI}. That library's "this month" scan called
 * {@code queryEvents(INTERVAL_MONTHLY, now)}: INTERVAL_MONTHLY is the constant 2, so it
 * started at 1970 and returned whatever the phone still held, which the server then
 * filed as the month. It also counted screen time only, so music played with the
 * screen off did not count.
 *
 * <p><b>Why days, and why not further back.</b> Android deletes its detailed event log
 * after ten days (UsageStatsDatabase.prune). No query can see past that, so one scan
 * cannot describe a month; instead every scan reports each day it can see, and the
 * server assembles months from them. Syncing at least weekly leaves no gaps.
 *
 * <p><b>Only whole days.</b> The oldest day the phone still holds is usually cut off
 * partway (the log is pruned at a bucket boundary, not at local midnight), so it is
 * left out rather than reported short. Today is reported as far as it has got; a later
 * sync replaces it with the fuller figure.
 *
 * <p>Slow on a phone with a lot of history: call it off the main thread.
 */
final class UsageReader {

    private UsageReader() {
    }

    /** What one scan found. */
    static final class Report {
        /** Every day the scan covered in full, oldest first, as yyyy-MM-dd; today is last. */
        final List<String> days;
        private final Map<String, Map<String, Long>> millisByPackage;

        Report(List<String> days, Map<String, Map<String, Long>> millisByPackage) {
            this.days = days;
            this.millisByPackage = millisByPackage;
        }

        static Report empty() {
            return new Report(Collections.<String>emptyList(), Collections.<String, Map<String, Long>>emptyMap());
        }

        boolean isEmpty() {
            return days.isEmpty();
        }

        /** Packages that were used at all during the covered days. */
        Set<String> usedPackages() {
            return millisByPackage.keySet();
        }

        /**
         * Whole minutes one app was used on one covered day; zero when it was not used.
         * Any use at all is at least one minute: the server reads zero as "not used",
         * and rounding forty seconds down would invent that.
         */
        int minutes(String packageName, String day) {
            Map<String, Long> byDay = millisByPackage.get(packageName);
            Long millis = byDay == null ? null : byDay.get(day);
            if (millis == null || millis <= 0) {
                return 0;
            }
            long minutes = Math.round(millis / 60000.0);
            return (int) Math.min(24 * 60, Math.max(1, minutes));
        }
    }

    static Report read(Context context) {
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return Report.empty();
        }
        TimeZone zone = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        // From the 1st of last month, so a sync early in a month can still finish
        // last month off. In practice the ten-day log is the limit, not this.
        long windowStart = startOfPreviousMonth(now, zone);

        // Where the phone's log actually begins: the oldest daily bucket it still has.
        // Empty also means usage access is off, in which case there is nothing to read.
        List<UsageStats> buckets = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, windowStart, now);
        if (buckets == null || buckets.isEmpty()) {
            return Report.empty();
        }
        long retainedFrom = Long.MAX_VALUE;
        for (UsageStats bucket : buckets) {
            retainedFrom = Math.min(retainedFrom, bucket.getFirstTimeStamp());
        }

        long coveredFrom = Math.max(windowStart, retainedFrom);
        long firstDay = startOfDay(coveredFrom, zone);
        if (firstDay < coveredFrom) {
            firstDay = addDays(firstDay, 1, zone);
        }
        if (firstDay >= now) {
            return Report.empty();
        }

        ForegroundTimeline timeline = new ForegroundTimeline(
                firstDay, now, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q, zone);
        UsageEvents events = manager.queryEvents(firstDay, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            timeline.onEvent(event.getPackageName(), event.getClassName(),
                    event.getEventType(), event.getTimeStamp());
        }
        return new Report(daysFrom(firstDay, now, zone), timeline.millisByDay());
    }

    /** Whether this Android version can see playback that runs without the app on screen. */
    static boolean countsBackgroundPlayback() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    private static List<String> daysFrom(long firstDay, long now, TimeZone zone) {
        SimpleDateFormat format = ForegroundTimeline.dayFormat(zone);
        List<String> days = new ArrayList<>();
        for (long day = firstDay; day <= now; day = addDays(day, 1, zone)) {
            days.add(format.format(new Date(day)));
        }
        return days;
    }

    private static long startOfPreviousMonth(long now, TimeZone zone) {
        Calendar cal = Calendar.getInstance(zone);
        cal.setTimeInMillis(startOfDay(now, zone));
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.add(Calendar.MONTH, -1);
        return cal.getTimeInMillis();
    }

    private static long startOfDay(long time, TimeZone zone) {
        Calendar cal = Calendar.getInstance(zone);
        cal.setTimeInMillis(time);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static long addDays(long dayStart, int days, TimeZone zone) {
        Calendar cal = Calendar.getInstance(zone);
        cal.setTimeInMillis(dayStart);
        cal.add(Calendar.DAY_OF_MONTH, days);
        return cal.getTimeInMillis();
    }
}
