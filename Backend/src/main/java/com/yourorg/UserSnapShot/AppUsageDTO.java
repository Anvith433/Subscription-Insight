package com.yourorg.UserSnapShot;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * One app in a usage report.
 *
 * <p>Current clients send {@code days}: minutes for each calendar day the phone could
 * see, zeros included. {@code usageMinutes} is the single total older clients send for
 * a TODAY/WEEK/MONTH window; it is only read when {@code days} is absent.
 */
@Getter @Setter
public class AppUsageDTO {
    private String packageName;
    private String appName;
    private int usageMinutes;
    private List<DayUsageDTO> days;
}
