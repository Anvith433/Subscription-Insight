package com.yourorg.UserSnapShot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yourorg.Subscriptions.Subscription;

@Repository
public interface UsageDayRepository extends JpaRepository<UsageDay, Long> {

    Optional<UsageDay> findBySubscriptionAndUsageDate(Subscription subscription, LocalDate usageDate);

    /** Every recorded day of one month, inclusive at both ends. */
    List<UsageDay> findBySubscriptionAndUsageDateBetween(Subscription subscription, LocalDate from, LocalDate to);

    /** The most recent day the app was actually used, or null if it never has been. */
    @Query("SELECT MAX(d.usageDate) FROM UsageDay d WHERE d.subscription = :subscription AND d.minutes > 0")
    LocalDate findLastUsedDate(@Param("subscription") Subscription subscription);
}
