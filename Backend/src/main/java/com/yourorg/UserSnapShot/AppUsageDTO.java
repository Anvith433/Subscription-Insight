package com.yourorg.UserSnapShot;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class AppUsageDTO {
    private String packageName; 
    private String appName;
    private int usageMinutes;
}