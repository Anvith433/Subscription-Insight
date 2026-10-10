package com.example.myjavaapplication;

import static com.example.myjavaapplication.ForegroundTimeline.ACTIVITY_PAUSED;
import static com.example.myjavaapplication.ForegroundTimeline.ACTIVITY_RESUMED;
import static com.example.myjavaapplication.ForegroundTimeline.ACTIVITY_STOPPED;
import static com.example.myjavaapplication.ForegroundTimeline.DEVICE_SHUTDOWN;
import static com.example.myjavaapplication.ForegroundTimeline.DEVICE_STARTUP;
import static com.example.myjavaapplication.ForegroundTimeline.FOREGROUND_SERVICE_START;
import static com.example.myjavaapplication.ForegroundTimeline.FOREGROUND_SERVICE_STOP;
import static com.example.myjavaapplication.ForegroundTimeline.SCREEN_NON_INTERACTIVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import org.junit.Test;

/**
 * The rules that turn Android's usage events into minutes per app per day. Times are
 * fixed on 3 and 4 October 2026 in India's time zone, so the midnight cases are real
 * local midnights and nothing depends on when the test runs.
 */
public class ForegroundTimelineTest {

    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    private static final String NETFLIX = "com.netflix.mediaclient";
    private static final String SPOTIFY = "com.spotify.music";
    private static final String DAY1 = "2026-10-03";
    private static final String DAY2 = "2026-10-04";

    /** Epoch millis for a time on 3 October (day 0) or 4 October (day 1), local. */
    private static long at(int day, int hour, int minute) {
        Calendar cal = Calendar.getInstance(IST);
        cal.clear();
        cal.set(2026, Calendar.OCTOBER, 3 + day, hour, minute, 0);
        return cal.getTimeInMillis();
    }

    /** A scan of the whole of the 3rd and 4th, on Android 10+ unless stated. */
    private static ForegroundTimeline scan(boolean android10) {
        return new ForegroundTimeline(at(0, 0, 0), at(2, 0, 0), android10, IST);
    }

    private static long minutes(Map<String, Map<String, Long>> result, String pkg, String day) {
        Map<String, Long> byDay = result.get(pkg);
        Long millis = byDay == null ? null : byDay.get(day);
        return millis == null ? 0 : millis / 60000;
    }

    @Test
    public void onScreenUntilStopped() {
        ForegroundTimeline t = scan(true);
        t.onEvent(NETFLIX, "Player", ACTIVITY_RESUMED, at(0, 20, 0));
        t.onEvent(NETFLIX, "Player", ACTIVITY_STOPPED, at(0, 20, 30));
        assertEquals(30, minutes(t.millisByDay(), NETFLIX, DAY1));
    }

    @Test
    public void pictureInPictureStillCountsOnAndroid10() {
        // Entering picture-in-picture pauses the activity, but it is still on screen
        // until it is stopped.
        ForegroundTimeline t = scan(true);
        t.onEvent(NETFLIX, "Player", ACTIVITY_RESUMED, at(0, 20, 0));
        t.onEvent(NETFLIX, "Player", ACTIVITY_PAUSED, at(0, 20, 10));
        t.onEvent(NETFLIX, "Player", ACTIVITY_STOPPED, at(0, 20, 40));
        assertEquals(40, minutes(t.millisByDay(), NETFLIX, DAY1));
    }

    @Test
    public void beforeAndroid10PauseEndsTheSession() {
        // No STOPPED event exists below Android 10, so PAUSED is all there is.
        ForegroundTimeline t = scan(false);
        t.onEvent(NETFLIX, "Player", ACTIVITY_RESUMED, at(0, 20, 0));
        t.onEvent(NETFLIX, "Player", ACTIVITY_PAUSED, at(0, 20, 10));
        assertEquals(10, minutes(t.millisByDay(), NETFLIX, DAY1));
    }

    @Test
    public void backgroundPlaybackCountsWithTheScreenOff() {
        ForegroundTimeline t = scan(true);
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_START, at(0, 22, 0));
        t.onEvent("android", null, SCREEN_NON_INTERACTIVE, at(0, 22, 5));
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_STOP, at(0, 23, 0));
        assertEquals(60, minutes(t.millisByDay(), SPOTIFY, DAY1));
    }

    @Test
    public void watchingWhilePlayingIsNotCountedTwice() {
        // On screen 10:00-10:30 and playing 10:15-11:00: one hour of use, not 75 min.
        ForegroundTimeline t = scan(true);
        t.onEvent(SPOTIFY, "Main", ACTIVITY_RESUMED, at(0, 10, 0));
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_START, at(0, 10, 15));
        t.onEvent(SPOTIFY, "Main", ACTIVITY_STOPPED, at(0, 10, 30));
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_STOP, at(0, 11, 0));
        assertEquals(60, minutes(t.millisByDay(), SPOTIFY, DAY1));
    }

    @Test
    public void screenOffEndsOnScreenTime() {
        // No STOPPED arrives (the lock screen went over it); turning the screen off
        // is what ends it, and the late STOPPED adds nothing.
        ForegroundTimeline t = scan(true);
        t.onEvent(NETFLIX, "Player", ACTIVITY_RESUMED, at(0, 10, 0));
        t.onEvent("android", null, SCREEN_NON_INTERACTIVE, at(0, 10, 20));
        t.onEvent(NETFLIX, "Player", ACTIVITY_STOPPED, at(0, 11, 0));
        assertEquals(20, minutes(t.millisByDay(), NETFLIX, DAY1));
    }

    @Test
    public void aSessionIsSplitAtLocalMidnight() {
        ForegroundTimeline t = scan(true);
        t.onEvent(NETFLIX, "Player", ACTIVITY_RESUMED, at(0, 23, 30));
        t.onEvent(NETFLIX, "Player", ACTIVITY_STOPPED, at(1, 0, 45));
        Map<String, Map<String, Long>> result = t.millisByDay();
        assertEquals(30, minutes(result, NETFLIX, DAY1));
        assertEquals(45, minutes(result, NETFLIX, DAY2));
    }

    @Test
    public void aSessionAlreadyRunningWhenTheScanStartsIsCreditedFromTheStart() {
        // Music started before midnight; the scan only sees it stop at 01:00.
        ForegroundTimeline t = scan(true);
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_STOP, at(0, 1, 0));
        assertEquals(60, minutes(t.millisByDay(), SPOTIFY, DAY1));
    }

    @Test
    public void shutdownEndsEverything() {
        ForegroundTimeline t = scan(true);
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_START, at(0, 9, 0));
        t.onEvent("android", null, DEVICE_SHUTDOWN, at(0, 9, 30));
        assertEquals(30, minutes(t.millisByDay(), SPOTIFY, DAY1));
    }

    @Test
    public void aStartupWithoutShutdownEndsSessionsAtTheLastKnownMoment() {
        // The battery died some time after 10:05; that is the last moment the phone
        // is known to have been on, so the session cannot run on until noon.
        ForegroundTimeline t = scan(true);
        t.onEvent(NETFLIX, "Player", ACTIVITY_RESUMED, at(0, 10, 0));
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_START, at(0, 10, 5));
        t.onEvent("android", null, DEVICE_STARTUP, at(0, 12, 0));
        Map<String, Map<String, Long>> result = t.millisByDay();
        assertEquals(5, minutes(result, NETFLIX, DAY1));
        assertEquals(0, minutes(result, SPOTIFY, DAY1));
    }

    @Test
    public void somethingStillPlayingRunsToTheEndOfTheScan() {
        ForegroundTimeline t = new ForegroundTimeline(at(0, 0, 0), at(0, 12, 0), true, IST);
        t.onEvent(SPOTIFY, "PlaybackService", FOREGROUND_SERVICE_START, at(0, 11, 0));
        assertEquals(60, minutes(t.millisByDay(), SPOTIFY, DAY1));
    }

    @Test
    public void appsNeverUsedAreAbsent() {
        ForegroundTimeline t = scan(true);
        t.onEvent(NETFLIX, "Player", ACTIVITY_RESUMED, at(0, 20, 0));
        t.onEvent(NETFLIX, "Player", ACTIVITY_STOPPED, at(0, 20, 30));
        Map<String, Map<String, Long>> result = t.millisByDay();
        assertFalse(result.containsKey(SPOTIFY));
        assertEquals(0, minutes(result, NETFLIX, DAY2));
    }
}
