package com.example.myjavaapplication;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class UsageRequest {
    @SerializedName("userId")
    private Long userId;
    
    @SerializedName("apps")
    private List<AppUsageInfo> apps;

    public UsageRequest(Long userId, List<AppUsageInfo> apps) {
        this.userId = userId;
        this.apps = apps;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public List<AppUsageInfo> getApps() { return apps; }
    public void setApps(List<AppUsageInfo> apps) { this.apps = apps; }
}
