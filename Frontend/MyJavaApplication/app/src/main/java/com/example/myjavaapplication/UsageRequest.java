package com.example.myjavaapplication;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class UsageRequest {
    @SerializedName("userId")
    private Long userId;
    
    @SerializedName("apps")
    private List<AppUsageInfo> apps;

    @SerializedName("period")
    private String period;

    public UsageRequest(Long userId, List<AppUsageInfo> apps, String period) {
        this.userId = userId;
        this.apps = apps;
        this.period = period;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public List<AppUsageInfo> getApps() { return apps; }
    public void setApps(List<AppUsageInfo> apps) { this.apps = apps; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
}
