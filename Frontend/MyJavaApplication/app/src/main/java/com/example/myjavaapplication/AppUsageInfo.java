package com.example.myjavaapplication;

import com.google.gson.annotations.SerializedName;

/**
 * One app's foreground time, as measured on the device and sent to PUT /usage.
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

    public AppUsageInfo(String packageName, String appName, long usageMinutes) {
        this.packageName = packageName;
        this.appName = appName;
        this.usageMinutes = usageMinutes;
    }

    public String getPackageName() { return packageName; }
    public String getAppName() { return appName; }
    public long getUsageMinutes() { return usageMinutes; }
}
