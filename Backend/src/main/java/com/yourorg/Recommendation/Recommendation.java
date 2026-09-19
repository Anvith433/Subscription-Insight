package com.yourorg.Recommendation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.yourorg.Users.User;
import com.yourorg.Subscriptions.Subscription;
import java.time.Instant;
import java.math.BigDecimal;
@Entity
@Table(name="recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Recommendation {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="id", nullable=false)
    private long id;

   @ManyToOne(fetch=FetchType.LAZY, optional=false)
   @JoinColumn(name="user_id", nullable=false)
   private User user;

   @ManyToOne(fetch=FetchType.LAZY, optional=false)
   @JoinColumn(name="subscription_id", nullable=false)
   private Subscription subscription;

   @Enumerated(EnumType.STRING)
   @Column(name="type", nullable=false)
   private Type type;

   /** Analytics month the verdict was computed from, formatted YYYY-MM. */
   @Column(name="period", length=7)
   private String period;

   @Column(name="reason", length=500)
   private String reason;

   /** Usage minutes observed in {@link #period}; the evidence for the verdict. */
   @Column(name="usage_minutes")
   private Integer usageMinutes;

   /** Subscription price normalised to a monthly amount (yearly plans divided by 12). */
   @Column(name="monthly_cost", precision=10, scale=2)
   private BigDecimal monthlyCost;

   /** monthlyCost / usage hours. Null when there is no usage to divide by. */
   @Column(name="cost_per_hour", precision=12, scale=2)
   private BigDecimal costPerHour;

   /** 0-100 value-for-money score; see SubscriptionValueScore. */
   @Column(name="value_score", precision=5, scale=2)
   private BigDecimal valueScore;

   /** Money freed per month if the user follows a CANCEL verdict. */
   @Column(name="estimated_monthly_savings", precision=10, scale=2)
   private BigDecimal estimatedMonthlySavings;

   // Matches the recommendations.confidence_score DECIMAL(5,2) column.
   @Column(name="confidence_score", precision=5, scale=2)
   private BigDecimal confidenceScore;

   @Enumerated(EnumType.STRING)
   @Column(name="status", nullable=false)
   private Status status;

   @Column(name="generated_at", nullable=false)
   private Instant generatedAt;

}
