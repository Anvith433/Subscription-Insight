package com.yourorg.UserSnapShot;

import lombok.Getter;
import lombok.Setter;

/**
 * One day's minutes for one app. {@code date} is ISO yyyy-MM-dd in the phone's own
 * calendar, kept a String so one malformed entry can be rejected on its own rather than
 * failing the whole request at deserialisation.
 */
@Getter @Setter
public class DayUsageDTO {
    private String date;
    private int minutes;
}
