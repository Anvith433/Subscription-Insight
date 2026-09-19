package com.yourorg.Subscriptions;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for creating or updating a subscription.
 *
 * <p>Enums arrive as strings so a bad value produces a clear 400 rather than a
 * Jackson deserialisation failure, and are matched case-insensitively -- the seed
 * data itself shipped {@code 'Music'} where the enum says {@code MUSIC}, so being
 * strict here would just reproduce that class of bug at the API boundary.
 */
public class SubscriptionRequestDTO {

    private String providerName;
    /** Android package id, e.g. com.netflix.mediaclient. The usage matcher's key. */
    private String packageName;
    private String category;
    private LocalDate startDate;
    private String renewalCycle;
    private LocalDate renewalDate;
    private BigDecimal price;
    private String currency;
    private String status;

    public SubscriptionRequestDTO() {
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public String getRenewalCycle() {
        return renewalCycle;
    }

    public void setRenewalCycle(String renewalCycle) {
        this.renewalCycle = renewalCycle;
    }

    public LocalDate getRenewalDate() {
        return renewalDate;
    }

    public void setRenewalDate(LocalDate renewalDate) {
        this.renewalDate = renewalDate;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
