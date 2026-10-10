package com.example.myjavaapplication;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Turns Android's usage event stream into time-in-use per app per calendar day.
 *
 * <p>Pure Java with no Android types, so the rules can be unit-tested on a plain JVM;
 * {@link UsageReader} feeds it the real events. Event types are the
 * {@code UsageEvents.Event} constants, copied as ints so the class compiles and runs
 * the same on every API level -- types a given Android version never emits simply
 * never arrive.
 *
 * <h2>What counts as use</h2>
 * An app is in use while any of its activities is on screen, or while it runs a
 * foreground service. The second is what catches music and video playing with the
 * screen off or another app open: Spotify plays through a foreground service, and
 * counting screen time alone reported it as barely used. The two are combined as a
 * union per app, so watching while also playing is not counted twice.
 *
 * <p>From Android 10 an activity stays on screen until it is STOPPED, not merely
 * PAUSED -- picture-in-picture and split screen pause an activity that is still
 * visible -- so with {@code stopEndsVisibility} set, PAUSED is ignored. Earlier
 * versions have no STOPPED event and PAUSED ends the session.
 *
 * <h2>Sessions the log does not close</h2>
 * Screen-off ends every on-screen session (services keep running: that is the
 * playback case). A shutdown ends everything. A startup without a recorded shutdown
 * -- a flat battery -- ends everything at the last event seen before it, the latest
 * moment the phone is known to have been running. Sessions still open when the scan
 * ends run to the end of the scan. A session that was already open when the scan
 * began is credited from the scan's start when its closing event arrives.
 */
final class ForegroundTimeline {

    static final int ACTIVITY_RESUMED = 1;
    static final int ACTIVITY_PAUSED = 2;
    static final int SCREEN_NON_INTERACTIVE = 16;
    static final int FOREGROUND_SERVICE_START = 19;
    static final int FOREGROUND_SERVICE_STOP = 20;
    static final int ACTIVITY_STOPPED = 23;
    static final int ACTIVITY_DESTROYED = 24;
    static final int DEVICE_SHUTDOWN = 26;
    static final int DEVICE_STARTUP = 27;

    private final long from;
    private final long to;
    private final boolean stopEndsVisibility;
    private final TimeZone zone;

    /** package -> class -> when it came on screen. */
    private final Map<String, Map<String, Long>> openActivities = new HashMap<>();
    /** package -> service class -> when it started. */
    private final Map<String, Map<String, Long>> openServices = new HashMap<>();
    /** package -> classes any event has mentioned, to recognise a session open since before {@link #from}. */
    private final Map<String, Set<String>> seen = new HashMap<>();
    /** package -> [start, end] spans, possibly overlapping until {@link #millisByDay()} merges them. */
    private final Map<String, List<long[]>> spans = new HashMap<>();
    private long lastTimestamp;

    /**
     * @param from               start of the scan, epoch millis
     * @param to                 end of the scan, epoch millis
     * @param stopEndsVisibility true on Android 10+, where ACTIVITY_STOPPED exists
     * @param zone               the calendar days are cut in
     */
    ForegroundTimeline(long from, long to, boolean stopEndsVisibility, TimeZone zone) {
        this.from = from;
        this.to = to;
        this.stopEndsVisibility = stopEndsVisibility;
        this.zone = zone;
        this.lastTimestamp = from;
    }

    /** Feed events in the order the system returns them, which is time order. */
    void onEvent(String pkg, String cls, int type, long time) {
        long t = Math.max(from, Math.min(to, time));

        if (type == DEVICE_STARTUP) {
            closeAll(openActivities, lastTimestamp);
            closeAll(openServices, lastTimestamp);
        } else if (type == DEVICE_SHUTDOWN) {
            closeAll(openActivities, t);
            closeAll(openServices, t);
        } else if (type == SCREEN_NON_INTERACTIVE) {
            closeAll(openActivities, t);
        }
        lastTimestamp = Math.max(lastTimestamp, t);

        if (pkg == null) {
            return;
        }
        String key = cls == null ? "" : cls;
        switch (type) {
            case ACTIVITY_RESUMED:
                open(openActivities, pkg, key, t);
                break;
            case ACTIVITY_PAUSED:
                if (!stopEndsVisibility) {
                    close(openActivities, pkg, key, t);
                }
                break;
            case ACTIVITY_STOPPED:
            case ACTIVITY_DESTROYED:
                close(openActivities, pkg, key, t);
                break;
            case FOREGROUND_SERVICE_START:
                open(openServices, pkg, key, t);
                break;
            case FOREGROUND_SERVICE_STOP:
                close(openServices, pkg, key, t);
                break;
            default:
                break;
        }
    }

    /**
     * Milliseconds in use per package per day ("yyyy-MM-dd" in {@code zone}). Closes
     * anything still open at the end of the scan; call once, after the last event.
     */
    Map<String, Map<String, Long>> millisByDay() {
        closeAll(openActivities, to);
        closeAll(openServices, to);

        SimpleDateFormat dayFormat = dayFormat(zone);
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (Map.Entry<String, List<long[]>> entry : spans.entrySet()) {
            Map<String, Long> byDay = new TreeMap<>();
            for (long[] span : merge(entry.getValue())) {
                addSplitByDay(span[0], span[1], byDay, dayFormat);
            }
            if (!byDay.isEmpty()) {
                result.put(entry.getKey(), byDay);
            }
        }
        return result;
    }

    static SimpleDateFormat dayFormat(TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(zone);
        return format;
    }

    private void open(Map<String, Map<String, Long>> open, String pkg, String cls, long t) {
        seenClasses(pkg).add(cls);
        Map<String, Long> byClass = open.get(pkg);
        if (byClass == null) {
            byClass = new HashMap<>();
            open.put(pkg, byClass);
        }
        if (!byClass.containsKey(cls)) {
            byClass.put(cls, t);
        }
    }

    private void close(Map<String, Map<String, Long>> open, String pkg, String cls, long t) {
        Map<String, Long> byClass = open.get(pkg);
        Long start = byClass == null ? null : byClass.remove(cls);
        if (start != null) {
            addSpan(pkg, start, t);
        } else if (!seenClasses(pkg).contains(cls)) {
            // Closed without ever being opened in this scan: it was already running
            // when the scan began.
            addSpan(pkg, from, t);
        }
        seenClasses(pkg).add(cls);
    }

    private void closeAll(Map<String, Map<String, Long>> open, long t) {
        for (Map.Entry<String, Map<String, Long>> pkg : open.entrySet()) {
            for (long start : pkg.getValue().values()) {
                addSpan(pkg.getKey(), start, Math.max(start, t));
            }
            pkg.getValue().clear();
        }
    }

    private Set<String> seenClasses(String pkg) {
        Set<String> classes = seen.get(pkg);
        if (classes == null) {
            classes = new HashSet<>();
            seen.put(pkg, classes);
        }
        return classes;
    }

    private void addSpan(String pkg, long start, long end) {
        if (end <= start) {
            return;
        }
        List<long[]> list = spans.get(pkg);
        if (list == null) {
            list = new ArrayList<>();
            spans.put(pkg, list);
        }
        list.add(new long[]{start, end});
    }

    /** The union of possibly overlapping spans, as disjoint spans in time order. */
    private static List<long[]> merge(List<long[]> input) {
        List<long[]> sorted = new ArrayList<>(input);
        Collections.sort(sorted, (a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] span : sorted) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && span[0] <= last[1]) {
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(new long[]{span[0], span[1]});
            }
        }
        return merged;
    }

    /** Adds one span to the days it covers, cutting it at each local midnight. */
    private void addSplitByDay(long start, long end, Map<String, Long> byDay, SimpleDateFormat dayFormat) {
        Calendar cal = Calendar.getInstance(zone);
        long cursor = start;
        while (cursor < end) {
            cal.setTimeInMillis(cursor);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            long pieceEnd = Math.min(end, cal.getTimeInMillis());
            String day = dayFormat.format(new Date(cursor));
            Long sofar = byDay.get(day);
            byDay.put(day, (sofar == null ? 0L : sofar) + (pieceEnd - cursor));
            cursor = pieceEnd;
        }
    }
}
