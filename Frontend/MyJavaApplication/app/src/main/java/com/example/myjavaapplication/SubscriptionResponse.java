package com.example.myjavaapplication;

import java.math.BigDecimal;

/**
 * One of the user's subscriptions.
 *
 * <p>A subset of the server's SubscriptionResponseDTO -- Gson ignores JSON fields a
 * model does not declare. It once held only the three fields the usage sync needs
 * (id, provider, package); the subscriptions screen displays the rest, so they are
 * declared now. {@code createdAt} and {@code updatedAt} are still left out because
 * nothing shows them, and declaring them would mean parsing {@code Instant} without
 * desugaring.
 *
 * <p>Dates are {@code String}: this module is minSdk 24 with no core library
 * desugaring, so a {@code java.time} type here would crash on API 24-25. The server
 * sends ISO {@code yyyy-MM-dd}, which is already display-ready.
 */
public class SubscriptionResponse {

    private Long id;
    private String providerName;

    /**
     * The Android package the subscription is for, e.g. {@code com.netflix.mediaclient}.
     * Nullable -- it is optional on the server, and a subscription added by hand may
     * only have a provider name, which is why the client matches on both.
     */
    private String packageName;

    private String category;
    private String startDate;
    private String renewalCycle;
    private String renewalDate;
    private BigDecimal price;

    /** Price normalised to a month, so a yearly plan is comparable with a monthly one. */
    private BigDecimal monthlyPrice;

    private String currency;

    /** ACTIVE, CANCELLED or PAUSED. */
    private String status;

    public Long getId() {
        return id;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getCategory() {
        return category;
    }

    public String getStartDate() {
        return startDate;
    }

    public String getRenewalCycle() {
        return renewalCycle;
    }

    public String getRenewalDate() {
        return renewalDate;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getMonthlyPrice() {
        return monthlyPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }
}
