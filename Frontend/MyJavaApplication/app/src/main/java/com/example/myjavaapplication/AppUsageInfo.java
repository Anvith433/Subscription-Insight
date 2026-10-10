package com.example.myjavaapplication;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * One app's time in use, as measured on the device and sent to PUT /usage.
 *
 * <p>{@code days} carries the measurement: minutes for every day the phone could
 * account for, zeros included, because a day the app sat unused is a fact the server
 * needs, not a gap. {@code usageMinutes} is their total, kept for logging and for
 * servers that predate per-day reports.
 *
 * <p>{@code packageName} is new. The backend matches an incoming app to a subscription
 * by package name first and only falls back to the display name, because the package
 * name is a stable OS identifier while the label is localised and marketing-dependent.
 * Sending only the label meant the fallback was always the path taken, which is how
 * "YouTube" failed to match a subscription stored as "YouTube Premium".
 */
public class AppUsageInfo {
    @SerializedName("packageName")
    private String packageName;

    @SerializedName("appName")
    private String appName;

    @SerializedName("usageMinutes")
    private long usageMinutes;

    @SerializedName("days")
    private List<Day> days;

    public AppUsageInfo(String packageName, String appName, List<Day> days) {
        this.packageName = packageName;
        this.appName = appName;
        this.days = days;
        long total = 0;
        for (Day day : days) {
            total += day.minutes;
        }
        this.usageMinutes = total;
    }

    public String getPackageName() { return packageName; }
    public String getAppName() { return appName; }
    public long getUsageMinutes() { return usageMinutes; }
    public List<Day> getDays() { return days; }

    /** One calendar day's minutes, the date as yyyy-MM-dd in the phone's time zone. */
    public static class Day {
        @SerializedName("date")
        final String date;

        @SerializedName("minutes")
        final int minutes;

        public Day(String date, int minutes) {
            this.date = date;
            this.minutes = minutes;
        }
    }
}
