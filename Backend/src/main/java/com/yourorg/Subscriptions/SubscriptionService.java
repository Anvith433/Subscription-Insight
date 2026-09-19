package com.yourorg.Subscriptions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.yourorg.Users.User;

/**
 * Manages a user's subscriptions.
 *
 * <p>This class previously held two methods, both broken and both replaced:
 *
 * <ul>
 *   <li>{@code syncUsageAndBilling} wrote {@code LocalDate.now().toString()} -- ten
 *       characters -- into {@code user_snapshots.period}, a {@code VARCHAR(7)}, so
 *       every call failed with MySQL error 1406. It also matched the incoming app
 *       against {@code provider_name} and did no billing at all despite its name.
 *       Usage handling now lives in {@code UserSnapShotService}, the author's own
 *       service, which was written for exactly this and was never wired up.</li>
 *   <li>{@code getUserDashboard} hardcoded {@code recommendationType("PENDING")},
 *       omitted price and currency, and dropped every subscription with zero usage --
 *       hiding precisely the subscriptions worth cancelling. Dashboard assembly now
 *       lives in {@code DashboardService}.</li>
 * </ul>
 *
 * <p>What replaces them is the CRUD the API documentation described but no controller
 * ever implemented: without it, subscriptions could only be added by hand in SQL, so
 * a real user had nothing to analyse.
 */
@Service
public class SubscriptionService {

    @Autowired
    private SubscriptionRepository subscriptionRepo;

    public List<Subscription> findByUser(Long userId) {
        return subscriptionRepo.findByUserId(userId);
    }

    public List<Subscription> findActiveByUser(Long userId) {
        return subscriptionRepo.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);
    }

    public Subscription requireOwned(Long userId, Long subscriptionId) {
        Subscription sub = subscriptionRepo.findById(subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
        // Compared through the id rather than the entity so a lazy proxy is fine.
        if (userId == null || sub.getUser() == null || sub.getUser().getId() != userId.longValue()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found");
        }
        return sub;
    }

    @Transactional
    public Subscription create(User owner, SubscriptionRequestDTO request) {
        requireText(request.getProviderName(), "providerName");
        requireText(request.getPackageName(), "packageName");

        subscriptionRepo.findByUserIdAndPackageNameIgnoreCase(owner.getId(), request.getPackageName().trim())
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "A subscription for " + request.getPackageName() + " already exists");
                });

        Subscription sub = new Subscription();
        sub.setUser(owner);
        apply(sub, request, true);
        sub.setStatus(parseStatus(request.getStatus(), SubscriptionStatus.ACTIVE));
        return subscriptionRepo.save(sub);
    }

    @Transactional
    public Subscription update(Long userId, Long subscriptionId, SubscriptionRequestDTO request) {
        Subscription sub = requireOwned(userId, subscriptionId);
        apply(sub, request, false);
        if (request.getStatus() != null) {
            sub.setStatus(parseStatus(request.getStatus(), sub.getStatus()));
        }
        sub.setUpdatedAt(Instant.now());
        return subscriptionRepo.save(sub);
    }

    /**
     * Marks a subscription cancelled rather than deleting it. The usage snapshots and
     * billing records attached to it are the evidence the recommendation was based
     * on; deleting the row would destroy the user's own spending history.
     */
    @Transactional
    public Subscription cancel(Long userId, Long subscriptionId) {
        Subscription sub = requireOwned(userId, subscriptionId);
        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setUpdatedAt(Instant.now());
        return subscriptionRepo.save(sub);
    }

    private void apply(Subscription sub, SubscriptionRequestDTO request, boolean creating) {
        if (request.getProviderName() != null) {
            sub.setProviderName(request.getProviderName().trim());
        }
        if (request.getPackageName() != null) {
            sub.setPackageName(request.getPackageName().trim());
        }
        if (request.getCategory() != null || creating) {
            sub.setCategory(parseCategory(request.getCategory()));
        }
        if (request.getRenewalCycle() != null || creating) {
            sub.setRenewalCycle(parseCycle(request.getRenewalCycle()));
        }
        if (request.getPrice() != null) {
            if (request.getPrice().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price cannot be negative");
            }
            sub.setPrice(request.getPrice());
        } else if (creating) {
            sub.setPrice(BigDecimal.ZERO);
        }
        if (request.getCurrency() != null) {
            String currency = request.getCurrency().trim().toUpperCase();
            if (currency.length() != 3) {
                // The column is CHAR(3); a longer value would be a 1406 truncation error.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be a 3-letter code");
            }
            sub.setCurrency(currency);
        } else if (creating) {
            sub.setCurrency("INR");
        }
        if (request.getStartDate() != null) {
            sub.setStartDate(request.getStartDate());
        } else if (creating) {
            sub.setStartDate(LocalDate.now());
        }
        if (request.getRenewalDate() != null) {
            sub.setRenewalDate(request.getRenewalDate());
        } else if (creating) {
            // renewal_date is NOT NULL, so it needs a value; the next cycle boundary
            // from the start date is the only defensible guess.
            sub.setRenewalDate(nextRenewal(sub.getStartDate(), sub.getRenewalCycle()));
        }
    }

    private LocalDate nextRenewal(LocalDate start, Renewal_Cycle cycle) {
        LocalDate from = start != null ? start : LocalDate.now();
        return cycle == Renewal_Cycle.YEARLY ? from.plusYears(1) : from.plusMonths(1);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    private Category parseCategory(String value) {
        // The column is NOT NULL and the enum has no neutral member, so there is no
        // honest default to fall back to -- guessing one would mislabel the
        // subscription in every category breakdown it appears in.
        requireText(value, "category");
        try {
            return Category.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: " + value);
        }
    }

    private Renewal_Cycle parseCycle(String value) {
        if (value == null || value.isBlank()) {
            return Renewal_Cycle.MONTHLY;
        }
        try {
            return Renewal_Cycle.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown renewalCycle: " + value);
        }
    }

    private SubscriptionStatus parseStatus(String value, SubscriptionStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return SubscriptionStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + value);
        }
    }
}
