package com.yourorg.UserSnapShot;
import java.util.List;


public class UsageRequest {
    private String period;
    private Long userId;
    private List<AppUsageDTO> apps;
    public UsageRequest() {
    }
      public UsageRequest(String period, Long userId, List<AppUsageDTO> apps) 
      {
        this.period = period;
        this.userId = userId;
        this.apps = apps;
      }
    public String getPeriod() {
        return period;
    }
    public void setPeriod(String period) {
        this.period = period;
    }
    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
    }
    public List<AppUsageDTO> getApps() {
        return apps;
    }
    public void setApps(List<AppUsageDTO> apps) {
        this.apps = apps;
    }
}