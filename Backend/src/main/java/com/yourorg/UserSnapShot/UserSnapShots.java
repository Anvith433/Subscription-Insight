package com.yourorg.UserSnapShot;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import com.yourorg.Subscriptions.Subscription;
import com.yourorg.common.DataSource;
@Entity
@Table(name="user_snapshots",
    uniqueConstraints = {
        @UniqueConstraint(  name = "uk_subscription_period",
            columnNames = {"subscription_id", "period"}
        )
    }
)


@Getter
@Setter

public class UserSnapShots {



    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    /**
     * Billing/analytics month this snapshot belongs to, formatted YYYY-MM
     * (java.time.YearMonth#toString). Must stay at length 7 to match the
     * user_snapshots.period column and the uk_subscription_period unique key --
     * writing a full YYYY-MM-DD date here overflows the column and MySQL rejects
     * the insert with error 1406 (Data too long).
     */
    @Column(nullable = false, length = 7)
    private String period; // YYYY-MM

    @Column(nullable = false)
    private int usageCount;

    /**
     * Which span the device scan that produced {@link #usageCount} covered, and how
     * much of the month that was. All three are null for rows written before V8, and
     * null is read as "unknown, assume the whole month" -- see
     * V8__record_usage_scan_window.sql for why that is the safe default and why they
     * are stored rather than derived from the read-time date.
     *
     * <p>They always describe the <i>same</i> observation as {@code usageCount}. The
     * merge rule in {@link UserSnapShotService} maintains that invariant: the trio is
     * only overwritten when the usage figure is, otherwise the pair would claim a
     * coverage the stored minutes never had.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private UsageWindow observedWindow;

    /** Days of {@link #period} the scan covered, counting both ends. */
    private Integer observedDays;

    /** Days elapsed in {@link #period} when the scan was taken, counting that day. */
    private Integer monthDaysElapsed;

    private Instant lastUsedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSource source;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

public UserSnapShots(){}
   
public UserSnapShots(Subscription subscription, String period, int usageCount, DataSource source) {
    this.subscription = subscription;
    this.period = period;
    this.usageCount = usageCount;
    this.source = source;
}

}

