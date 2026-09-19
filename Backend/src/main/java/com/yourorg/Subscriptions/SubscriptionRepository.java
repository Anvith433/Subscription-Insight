package com.yourorg.Subscriptions;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserId(Long userId);

    List<Subscription> findByUserIdAndStatus(Long userId, SubscriptionStatus status);

    Optional<Subscription> findByUserIdAndPackageName(Long userId, String packageName);

    /**
     * Primary matcher for incoming Android usage: the package name is a stable
     * OS-level identifier, unlike the human-readable app label.
     */
    Optional<Subscription> findByUserIdAndPackageNameIgnoreCase(Long userId, String packageName);

    /** Fallback matcher for clients that only send a display name. */
    Optional<Subscription> findByUserIdAndProviderNameIgnoreCase(Long userId, String providerName);
}
