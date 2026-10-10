package com.yourorg.UserSnapShot;

import java.time.Instant;
import java.time.LocalDate;

import com.yourorg.Subscriptions.Subscription;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Minutes one subscription's app was used on one calendar day, as measured on the
 * device. The building block months are assembled from -- see V9__daily_usage.sql for
 * why a month can only be pieced together from days.
 *
 * <p>Zero is a measurement: the day was scanned and the app was not used. A day with
 * no row was never scanned.
 */
@Entity
@Table(name = "usage_days",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_usage_days_subscription_date",
        columnNames = {"subscription_id", "usage_date"}))
@Getter
@Setter
public class UsageDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    private int minutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UsageDay() {}
}
