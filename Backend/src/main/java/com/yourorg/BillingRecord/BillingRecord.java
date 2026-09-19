package com.yourorg.BillingRecord;
import jakarta.persistence.*;
import com.yourorg.Subscriptions.Subscription;
import com.yourorg.common.DataSource;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="billing_records")
 

public class BillingRecord { 
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="id", nullable=false)
    private long id;


    @Column(name="amount",precision=10, scale=2)
    private BigDecimal amount;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="subscription_id", nullable=false)
    private Subscription subscription;

    @Column(length=3, nullable=false)
    private String currency;

    @Column(name="billing_period", nullable=false, length=7)
    private String billingPeriod; 

    @Column(name="paid_at", nullable=false)
    private Instant paidAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false)
    private DataSource source;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt;

   public BillingRecord(){}

    public BillingRecord(long id , BigDecimal amount,Subscription subscription, String currency, String billingPeriod, Instant paidAt,PaymentMethod paymentMethod, DataSource source) {
        this.id = id;
        this.subscription = subscription;
        this.amount = amount;
        this.currency = currency;
        this.billingPeriod = billingPeriod;
        this.paidAt = paidAt;
        this.paymentMethod = paymentMethod;
        this.source = source;
        this.createdAt = Instant.now();
    }
    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }
    public BigDecimal getAmount() {
        return amount;
    }
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
    public Subscription getSubscription() {
        return subscription;
    }
    public void setSubscription(Subscription subscription) {
        this.subscription = subscription;
    }
    public String getCurrency() {
        return currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public String getBillingPeriod() {
        return billingPeriod;
    }
    public void setBillingPeriod(String billingPeriod) {
        this.billingPeriod = billingPeriod;
    }
    public Instant getPaidAt() {
        return paidAt;
    }
    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }
    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }
    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
    public DataSource getSource() {
        return source;
    }
    public void setSource(DataSource source) {
        this.source = source;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    
    
}
