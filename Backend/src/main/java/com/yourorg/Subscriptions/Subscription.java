package com.yourorg.Subscriptions;
import com.yourorg.Users.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

   
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider_name", nullable = false, length = 255)
    private String providerName;

   
    
    @Column(name = "package_name", nullable = false, length = 255)
    private String packageName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "renewal_cycle", nullable = false, length = 20)
    private Renewal_Cycle renewalCycle;

    @Enumerated(EnumType.STRING)
    @Column(name="Category", nullable=false, length=50)
    private Category category;

    @Column(name = "renewal_date", nullable = false)
    private LocalDate renewalDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

   
    public Subscription() {
        
    }

    public Subscription(
            long id,
            User user,
            String providerName,
            Category category,
            String packageName,
            LocalDate startDate,
            Renewal_Cycle renewalCycle,
            LocalDate renewalDate,
            BigDecimal price,
            String currency,
            SubscriptionStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id=id;
        this.user = user;
        this.providerName = providerName;
        this.category = category;
        this.packageName = packageName;
        this.startDate = startDate;
        this.renewalCycle = renewalCycle;
        this.renewalDate = renewalDate;
        this.price = price;
        this.currency = currency;
        // Honour the caller's arguments. Previously these two lines hardcoded
        // ACTIVE / Instant.now(), silently discarding the status and createdAt
        // that were passed in, which made the constructor unusable for
        // reconstructing an existing subscription.
        this.status = status != null ? status : SubscriptionStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt;
    }

    

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    // Required to create a subscription for a user: user_id is NOT NULL, so
    // without a setter no subscription could ever be persisted through the API.
    public void setUser(User user) {
        this.user = user;
    }

    /**
     * Price normalised to a monthly amount so subscriptions on different renewal
     * cycles can be compared and summed. YEARLY plans are divided by 12; FREE
     * plans cost nothing.
     */
    public BigDecimal getMonthlyPrice() {
        if (renewalCycle == Renewal_Cycle.FREE || price == null) {
            return BigDecimal.ZERO;
        }
        if (renewalCycle == Renewal_Cycle.YEARLY) {
            return price.divide(new java.math.BigDecimal("12"), 2, java.math.RoundingMode.HALF_UP);
        }
        return price;
    }

    public String getProviderName() {
        return providerName;
    }

    public Category getCategory() {
        return category;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public Renewal_Cycle getRenewalCycle() {
        return renewalCycle;
    }

    public LocalDate getRenewalDate() {
        return renewalDate;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

   
    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public void setRenewalCycle(Renewal_Cycle renewalCycle) {
        this.renewalCycle = renewalCycle;
    }

    public void setRenewalDate(LocalDate renewalDate) {
        this.renewalDate = renewalDate;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
  
    
}
