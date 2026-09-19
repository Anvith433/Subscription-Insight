package com.example.myjavaapplication;

import java.math.BigDecimal;

/**
 * Request body for POST and PUT /api/subscriptions, mirroring the server's
 * SubscriptionRequestDTO.
 *
 * <p>Two deliberate type choices:
 *
 * <ul>
 *   <li>Dates are {@code String} in ISO {@code yyyy-MM-dd}, not {@code LocalDate}. This
 *       module is minSdk 24 without core library desugaring, so a {@code java.time}
 *       reference compiles and then throws NoClassDefFoundError on API 24-25. Spring
 *       Boot's default Jackson setup reads and writes {@code LocalDate} in exactly this
 *       format, so the wire contract is unchanged.</li>
 *   <li>Price is {@code BigDecimal}, available at every API level. Gson writes it as an
 *       unquoted number preserving scale, so {@code 199.00} arrives as {@code 199.00}
 *       rather than being rounded through a {@code double}.</li>
 * </ul>
 *
 * <p>Null fields matter: the server's {@code apply()} only overwrites a column when the
 * corresponding field is present, which is what makes {@link #statusOnly} safe.
 */
public class SubscriptionRequestBody {

    private String providerName;
    /** Android package id. Comes from PackageManager, never from typing. */
    private String packageName;
    private String category;
    private String startDate;
    private String renewalCycle;
    private String renewalDate;
    private BigDecimal price;
    private String currency;
    private String status;

    private SubscriptionRequestBody() {
    }

    /**
     * A new subscription. {@code renewalDate} and {@code status} are left null on
     * purpose -- the server derives the next renewal from the start date and cycle, and
     * defaults the status to ACTIVE. Sending our own guesses would only be a chance to
     * disagree with it.
     */
    public static SubscriptionRequestBody create(
            String providerName,
            String packageName,
            String category,
            String renewalCycle,
            String startDate,
            BigDecimal price,
            String currency) {
        SubscriptionRequestBody b = new SubscriptionRequestBody();
        b.providerName = providerName;
        b.packageName = packageName;
        b.category = category;
        b.renewalCycle = renewalCycle;
        b.startDate = startDate;
        b.price = price;
        b.currency = currency;
        return b;
    }

    /**
     * A body that changes nothing but the status -- used to reactivate a cancelled
     * subscription. Every other field stays null so the server leaves the stored price,
     * category and dates alone.
     */
    public static SubscriptionRequestBody statusOnly(String status) {
        SubscriptionRequestBody b = new SubscriptionRequestBody();
        b.status = status;
        return b;
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

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }
}
