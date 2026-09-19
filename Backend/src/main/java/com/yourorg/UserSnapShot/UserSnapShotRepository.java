package com.yourorg.UserSnapShot;
import org.springframework.stereotype.Repository;
import com.yourorg.Subscriptions.Subscription;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface UserSnapShotRepository extends JpaRepository<UserSnapShots, Long> {

    Optional<UserSnapShots> findBySubscriptionAndPeriod(Subscription subscription, String period);

    /**
     * Loads the snapshots for many subscriptions across several periods in one
     * query. The dashboard uses this instead of querying per subscription inside
     * a loop, which was an N+1 (one SELECT per subscription, plus another per
     * recommendation).
     */
    List<UserSnapShots> findBySubscriptionIdInAndPeriodIn(List<Long> subscriptionIds, List<String> periods);

    /**
     * Most recent period (YYYY-MM) for which this user has any usage data.
     * Lets the dashboard fall back to the newest month that actually has data
     * instead of showing an empty current month.
     */
    @Query("SELECT MAX(s.period) FROM UserSnapShots s WHERE s.subscription.user.id = :userId")
    String findLatestPeriodForUser(@Param("userId") Long userId);

    /** Full history for one subscription, newest period first. */
    List<UserSnapShots> findBySubscriptionIdOrderByPeriodDesc(Long subscriptionId);
}
