package com.example.myjavaapplication;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * The body of PUT /usage.
 *
 * <p>The call was previously typed {@code Call<Void>}, so a sync that matched none of
 * the reported apps to a subscription still showed "Sync Successful!" -- the most
 * common real failure was indistinguishable from success. The server now says which
 * providers it stored usage for and which apps it ignored, and this model surfaces
 * that.
 */
public class UsageSyncResponse {
    /** The month the usage was recorded against, YYYY-MM. */
    @SerializedName("period")
    private String period;

    /** Provider names whose usage was stored. */
    @SerializedName("synced")
    private List<String> synced;

    /** Reported apps that are not subscriptions for this user. */
    @SerializedName("skipped")
    private List<String> skipped;

    public String getPeriod() { return period; }

    public int getSyncedCount() { return synced == null ? 0 : synced.size(); }

    public int getSkippedCount() { return skipped == null ? 0 : skipped.size(); }

    public List<String> getSynced() { return synced; }

    public List<String> getSkipped() { return skipped; }
}
