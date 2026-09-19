package com.yourorg.Subscriptions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A subscription as returned to the client.
 *
 * <p>Returning the {@link Subscription} entity instead would serialise its
 * {@code user} association: outside a transaction that is a
 * LazyInitializationException, and inside one it embeds the owner's record --
 * password hash included -- in every row.
 */
public record SubscriptionResponseDTO(
        Long id,
        String providerName,
        String packageName,
        String category,
        LocalDate startDate,
        String renewalCycle,
        LocalDate renewalDate,
        BigDecimal price,
        /** Price normalised to a month, so yearly plans are comparable. */
        BigDecimal monthlyPrice,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static SubscriptionResponseDTO from(Subscription sub) {
        return new SubscriptionResponseDTO(
                sub.getId(),
                sub.getProviderName(),
                sub.getPackageName(),
                sub.getCategory() == null ? null : sub.getCategory().name(),
                sub.getStartDate(),
                sub.getRenewalCycle() == null ? null : sub.getRenewalCycle().name(),
                sub.getRenewalDate(),
                sub.getPrice(),
                sub.getMonthlyPrice(),
                sub.getCurrency(),
                sub.getStatus() == null ? null : sub.getStatus().name(),
                sub.getCreatedAt(),
                sub.getUpdatedAt());
    }
}
