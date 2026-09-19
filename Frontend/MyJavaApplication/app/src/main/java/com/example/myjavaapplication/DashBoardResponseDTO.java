package com.example.myjavaapplication;

import com.google.gson.annotations.SerializedName;

/**
 * One dashboard row: what a subscription costs, how much it was used, and what the
 * engine concluded.
 *
 * <p>The fields below {@code recommendationType} are the ones that make a verdict
 * explainable -- the value score, the cost per hour it was derived from, the saving
 * that cancelling would actually free, and how confident the engine is. They were
 * being returned by the server and dropped on the floor here.
 *
 * <p>{@code hasUsageData} matters more than its size suggests: without it a
 * subscription with no synced usage is indistinguishable from one used for zero
 * minutes, and the two warrant opposite advice.
 */
public class DashBoardResponseDTO {
    @SerializedName("providerName")
    private String providerName;

    @SerializedName("packageName")
    private String packageName;

    @SerializedName("price")
    private double price;

    @SerializedName("currency")
    private String currency;

    /** Price normalised to a monthly amount, so yearly plans are comparable. */
    @SerializedName("monthlyCost")
    private Double monthlyCost;

    @SerializedName("usageMinutes")
    private long usageMinutes;

    @SerializedName("billingPeriod")
    private String billingPeriod;

    /** False when no snapshot exists for the period: "unknown", not "zero". */
    @SerializedName("hasUsageData")
    private boolean hasUsageData;

    @SerializedName("recommendationType")
    private String recommendationType;

    @SerializedName("reason")
    private String reason;

    @SerializedName("confidenceScore")
    private Double confidenceScore;

    /** 0-100 value-for-money score. */
    @SerializedName("valueScore")
    private Double valueScore;

    @SerializedName("costPerHour")
    private Double costPerHour;

    @SerializedName("estimatedMonthlySavings")
    private Double estimatedMonthlySavings;

    public DashBoardResponseDTO() {}

    public String getProviderName() { return providerName != null ? providerName : "Unknown"; }
    public String getPackageName() { return packageName; }
    public double getPrice() { return price; }
    public String getCurrency() { return currency != null ? currency : "INR"; }
    public Double getMonthlyCost() { return monthlyCost; }
    public long getUsageMinutes() { return usageMinutes; }
    public String getBillingPeriod() { return billingPeriod; }
    public boolean hasUsageData() { return hasUsageData; }
    public String getRecommendationType() { return recommendationType != null ? recommendationType : "CONSIDER"; }
    public String getReason() { return reason != null ? reason : "Analyzing..."; }
    public Double getConfidenceScore() { return confidenceScore; }
    public Double getValueScore() { return valueScore; }
    public Double getCostPerHour() { return costPerHour; }
    public Double getEstimatedMonthlySavings() { return estimatedMonthlySavings; }

    // This ensures the BarChart sees the same minutes as the list
    public long getUsageTime() { return usageMinutes; }

    /** "1 h 12 min used", or an explicit "no usage synced" rather than a bare "0 mins". */
    public String getUsageLabel() {
        if (!hasUsageData) {
            return "No usage synced" + (billingPeriod == null ? "" : " for " + billingPeriod);
        }
        if (usageMinutes < 60) {
            return usageMinutes + " min used";
        }
        return (usageMinutes / 60) + " h " + (usageMinutes % 60) + " min used";
    }

    /** "INR 129.00", formatted so a price never renders as "129.0". */
    public String getPriceLabel() {
        return getCurrency() + " " + String.format(java.util.Locale.US, "%.2f", price);
    }
}
