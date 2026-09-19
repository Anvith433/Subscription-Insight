package com.yourorg.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findBySubscriptionProviderName(String providerName);

    /**
     * The engine keeps exactly one current recommendation per (user,
     * subscription) -- enforced by uk_recommendation_user_subscription -- so this
     * Optional lookup can never hit NonUniqueResultException.
     */
    Optional<Recommendation> findByUserIdAndSubscriptionId(Long userId, Long subscriptionId);

    /** All current recommendations for a user, fetched in one query for the dashboard. */
    List<Recommendation> findByUserId(Long userId);

    // Removed: `Recommendation deleteById(int id)`. A derived delete method that
    // declares an entity return type and an int (not Long) id does not match the
    // JpaRepository contract; JpaRepository already provides deleteById(Long).
}
