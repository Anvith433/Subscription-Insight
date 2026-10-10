package com.yourorg.Recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides what a user should do about a subscription, and explains why.
 *
 * <p>This class is deliberately free of Spring wiring and database access: it is a
 * pure function from {@link UsageFacts} to {@link Verdict}. Every number quoted in
 * the explanation is one the decision actually used, so the "why is it
 * recommending this" question is answerable from the stored row alone.
 *
 * <h2>The model</h2>
 * The central metric is <b>cost per hour of actual use</b>:
 * {@code monthlyPrice / (usageMinutes / 60)}. That is turned into a 0-100
 * <b>value score</b> which falls linearly to zero as cost per hour approaches
 * {@link EngineThresholds#maxAcceptableCostPerHour()}. The score, plus absolute
 * usage, plus how the subscription compares to the rest of the user's portfolio,
 * selects the verdict:
 *
 * <pre>
 *   no snapshot for the month      -> CONSIDER  (we genuinely do not know)
 *   price is zero (free plan)      -> KEEP      (nothing to justify)
 *   started during the month       -> CONSIDER  (partial data, do not act yet)
 *   scan covered part of the month -> KEEP      if the usage already justifies it,
 *                                     CONSIDER  otherwise (never CANCEL on a
 *                                               lower bound)
 *   zero minutes used              -> CANCEL    (saving == full price)
 *   under 1 hour used              -> CANCEL    (paying for something unused)
 *   score >= 70                    -> KEEP
 *   score >= 40                    -> CONSIDER
 *   score <  40                    -> DOWNGRADE if priced above the user's median,
 *                                     otherwise CONSIDER (already cheap; there may
 *                                     be no cheaper tier to move to)
 * </pre>
 *
 * Month-over-month trend never overrides the verdict; it is reported in the
 * explanation and nudges confidence up or down depending on whether it agrees
 * with the verdict.
 *
 * <h2>Partial scans are read as a lower bound</h2>
 * The device reports one total for whatever span it was asked about, and the user
 * chooses that span. A "Today" scan proves usage was <i>at least</i> what it
 * measured; it says nothing about the other twenty-nine days. So a partial scan can
 * confirm a subscription is worth keeping -- more usage only strengthens that -- but
 * it can never condemn one, and no figure is ever extrapolated from it. Confidence
 * scales with how much of the month was actually looked at.
 *
 * <h2>Deliberately not implemented</h2>
 * {@link Type#UPGRADE} is never returned, and DOWNGRADE never claims a specific
 * saving. Both would require a catalogue of each provider's plans and prices,
 * which this system does not have. Inventing a number there would be a guess
 * dressed up as analysis.
 */
@Component
public class RecommendationEngine {

    private static final BigDecimal SIXTY = new BigDecimal("60");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE = new BigDecimal("12");

    /** Matches the recommendations.reason column width. */
    private static final int REASON_MAX = 500;

    /**
     * Confidence in a provisional verdict runs from {@code FLOOR} at no coverage to
     * {@code FLOOR + RANGE} at full coverage. The floor is the same 0.20 used when
     * there is no snapshot at all -- a one-day scan is barely more informative -- and
     * the ceiling stays below the 0.65 a full month's data earns, so a provisional
     * CONSIDER never outranks a measured one.
     *
     * <p>Private to the engine rather than part of {@link EngineThresholds}: these are
     * dimensionless fractions of a month, so unlike {@code maxAcceptableCostPerHour}
     * they need no per-currency calibration and there is nothing for a deployment to
     * tune.
     */
    private static final BigDecimal PARTIAL_CONFIDENCE_FLOOR = new BigDecimal("0.20");
    private static final BigDecimal PARTIAL_CONFIDENCE_RANGE = new BigDecimal("0.45");

    private final EngineThresholds thresholds;

    @Autowired
    public RecommendationEngine() {
        this(EngineThresholds.defaults());
    }

    public RecommendationEngine(EngineThresholds thresholds) {
        this.thresholds = thresholds;
    }

    public Verdict assess(UsageFacts f) {
        final String provider = f.providerName() == null ? "This subscription" : f.providerName();
        final BigDecimal price = f.monthlyPrice() == null ? BigDecimal.ZERO : f.monthlyPrice();

        // --- Case 1: no snapshot at all. "We have no data" is not "usage was zero",
        // and reporting it as CANCEL would be a fabricated conclusion.
        if (f.usageMinutes() == null) {
            return new Verdict(
                    Type.CONSIDER,
                    trim("No usage has been synced for " + f.period() + " yet, so " + provider
                            + " cannot be assessed. Run a sync from the app to get a recommendation."),
                    bd("0.20"), null, null, BigDecimal.ZERO);
        }

        final int minutes = Math.max(0, f.usageMinutes());

        // --- Case 2: a free plan has no cost to justify, whatever the usage.
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return new Verdict(
                    Type.KEEP,
                    trim(provider + " is on a free plan, so there is no cost to justify. Used "
                            + minutes + " min in " + f.period() + "."),
                    bd("0.90"), HUNDRED, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // --- Metrics shared by the remaining branches.
        final BigDecimal hours = BigDecimal.valueOf(minutes).divide(SIXTY, 4, RoundingMode.HALF_UP);
        // Derived from minutes directly rather than from the rounded `hours` above:
        // dividing by a 4-decimal approximation of 1/3 h turned an exact 1947.00 into
        // 1947.19. `hours` is kept for display and for the minimum-hours threshold.
        final BigDecimal costPerHour = minutes == 0
                ? null
                : price.multiply(SIXTY).divide(BigDecimal.valueOf(minutes), 2, RoundingMode.HALF_UP);
        final BigDecimal score = valueScore(costPerHour);
        final String money = money(f.currency(), price);
        final String yearly = money(f.currency(), price.multiply(TWELVE));
        final String trend = trendSentence(f);
        final int trendDirection = trendDirection(f);
        final String usageText = minutes + " min (" + hours.setScale(1, RoundingMode.HALF_UP) + " h)";
        // "n/a" when minutes is zero, which the branches that quote it never hit.
        final String perHour = money(f.currency(), costPerHour);

        // --- Case 3: the subscription started during (or after) the month being
        // assessed, so the month is partial and any verdict would be premature.
        if (f.startDate() != null && !YearMonth.from(f.startDate()).isBefore(f.period())) {
            return new Verdict(
                    Type.CONSIDER,
                    trim(provider + " started on " + f.startDate() + ", so " + f.period()
                            + " is only a partial month. Used " + minutes
                            + " min so far; the recommendation will firm up after a full billing cycle."),
                    bd("0.35"), score, costPerHour, BigDecimal.ZERO);
        }

        // --- Case 4: the scan itself only looked at part of the month, so the stored
        // minutes are a floor rather than a total. This sits ahead of both CANCEL
        // branches on purpose: "I opened nothing today" is not "I opened nothing this
        // month", and the difference between those two used to be invisible here.
        //
        // Case 3 keeps priority over this one because "started on the 20th" is the
        // more specific and more useful explanation when both apply.
        if (f.isPartialScan()) {
            final String coverageText = f.observedDays() + " of " + f.monthDaysElapsed()
                    + " days elapsed in " + f.period();

            // A lower bound that already clears the keep threshold is safe to act on:
            // the unscanned days can only add usage, which can only improve the score.
            if (minutes > 0 && score.compareTo(thresholds.keepScore()) >= 0) {
                return new Verdict(
                        Type.KEEP,
                        trim("Used at least " + usageText + " in " + f.period()
                                + ", measured over " + coverageText + ". That is at most "
                                + perHour + " per hour on a " + money
                                + "/month plan - already good value (score "
                                + score.setScale(0, RoundingMode.HALF_UP)
                                + "/100), and the days not scanned can only improve it." + trend),
                        confidence(bd("0.85"), Type.KEEP, trendDirection),
                        score, costPerHour, BigDecimal.ZERO);
            }

            // Everything else waits. Notably zero minutes: without this the next
            // branch would read an unscanned month as an unused one and return CANCEL
            // at 0.95 confidence. No figure is extrapolated to fill the gap, and no
            // saving is claimed, because neither would be a measurement.
            return new Verdict(
                    Type.CONSIDER,
                    trim("Based on a partial scan covering " + coverageText + ". Used at least "
                            + usageText + " so far, which would not justify " + money
                            + "/month on its own - but the days not recorded yet cannot be ruled "
                            + "out. Android keeps only about ten days of usage, so syncing at least "
                            + "once a week covers the whole month." + trend),
                    partialConfidence(f, trendDirection),
                    score, costPerHour, BigDecimal.ZERO);
        }

        // --- Case 5: paid for, never touched. The clearest possible cancel signal.
        if (minutes == 0) {
            String lastUse = f.lastUsedAt() == null
                    ? " There is no record of it ever being used."
                    : " Last recorded use was " + daysSinceLastUse(f) + " days ago.";
            return new Verdict(
                    Type.CANCEL,
                    trim("Not used at all in " + f.period() + " while costing " + money
                            + "/month. Cancelling frees " + money + " a month (" + yearly + " a year)."
                            + lastUse + trend),
                    confidence(bd("0.95"), Type.CANCEL, trendDirection),
                    score, costPerHour, price);
        }

        // --- Case 6: technically used, but not enough to call it used at all.
        if (hours.compareTo(thresholds.minMonthlyHoursForValue()) < 0) {
            return new Verdict(
                    Type.CANCEL,
                    trim("Used only " + usageText + " in " + f.period() + ", which works out to "
                            + perHour + " per hour on a " + money + "/month plan. Cancelling frees "
                            + money + " a month (" + yearly + " a year)." + trend),
                    confidence(bd("0.85"), Type.CANCEL, trendDirection),
                    score, costPerHour, price);
        }

        // --- Case 7: genuine usage. The value score decides.
        if (score.compareTo(thresholds.keepScore()) >= 0) {
            return new Verdict(
                    Type.KEEP,
                    trim("Used " + usageText + " in " + f.period() + " at " + perHour
                            + " per hour on a " + money + "/month plan - good value (score "
                            + score.setScale(0, RoundingMode.HALF_UP) + "/100)." + trend),
                    confidence(bd("0.85"), Type.KEEP, trendDirection),
                    score, costPerHour, BigDecimal.ZERO);
        }

        if (score.compareTo(thresholds.considerScore()) >= 0) {
            return new Verdict(
                    Type.CONSIDER,
                    trim("Used " + usageText + " in " + f.period() + " at " + perHour
                            + " per hour on a " + money + "/month plan (score "
                            + score.setScale(0, RoundingMode.HALF_UP)
                            + "/100). Worth watching - it is paying for itself, but only just."
                            + trend),
                    confidence(bd("0.65"), Type.CONSIDER, trendDirection),
                    score, costPerHour, BigDecimal.ZERO);
        }

        // Poor value per hour. Suggest a cheaper tier only when this subscription is
        // actually expensive relative to the user's others -- if it is already one of
        // their cheaper ones, there may be nothing cheaper to move to.
        BigDecimal median = f.portfolioMedianPrice();
        boolean pricedAboveMedian = median != null && price.compareTo(median) > 0;

        if (pricedAboveMedian) {
            return new Verdict(
                    Type.DOWNGRADE,
                    trim("Used " + usageText + " in " + f.period() + ", but at " + perHour
                            + " per hour on a " + money + "/month plan the value score is only "
                            + score.setScale(0, RoundingMode.HALF_UP) + "/100, and it costs more than your median "
                            + "subscription (" + money(f.currency(), median)
                            + "). A cheaper tier would likely cover this much use." + trend),
                    confidence(bd("0.70"), Type.DOWNGRADE, trendDirection),
                    score, costPerHour, BigDecimal.ZERO);
        }

        return new Verdict(
                Type.CONSIDER,
                trim("Used " + usageText + " in " + f.period() + " at " + perHour
                        + " per hour on a " + money + "/month plan (score "
                        + score.setScale(0, RoundingMode.HALF_UP)
                        + "/100). Poor value per hour, though it is already one of your cheaper "
                        + "subscriptions, so there may be no lower tier to move to." + trend),
                confidence(bd("0.60"), Type.CONSIDER, trendDirection),
                score, costPerHour, BigDecimal.ZERO);
    }

    /**
     * 100 when the subscription is effectively free per hour, falling linearly to 0
     * at {@link EngineThresholds#maxAcceptableCostPerHour()}. Zero usage scores 0.
     */
    private BigDecimal valueScore(BigDecimal costPerHour) {
        if (costPerHour == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal ratio = costPerHour.divide(thresholds.maxAcceptableCostPerHour(), 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }
        return HUNDRED.multiply(BigDecimal.ONE.subtract(ratio)).setScale(2, RoundingMode.HALF_UP);
    }

    /** -1 declining, +1 rising, 0 flat or unknown. */
    private int trendDirection(UsageFacts f) {
        Integer prior = f.priorUsageMinutes();
        if (prior == null || prior <= 0 || f.usageMinutes() == null) {
            return 0;
        }
        long pct = percentChange(f.usageMinutes(), prior);
        if (pct <= -10) return -1;
        if (pct >= 10) return 1;
        return 0;
    }

    private String trendSentence(UsageFacts f) {
        Integer prior = f.priorUsageMinutes();
        if (prior == null || prior <= 0 || f.usageMinutes() == null) {
            return "";
        }
        long pct = percentChange(f.usageMinutes(), prior);
        if (pct <= -10) return " Usage is down " + Math.abs(pct) + "% versus the previous month.";
        if (pct >= 10) return " Usage is up " + pct + "% versus the previous month.";
        return " Usage is roughly flat versus the previous month.";
    }

    private long percentChange(int now, int prior) {
        return Math.round((now - prior) * 100.0 / prior);
    }

    /**
     * How much to trust a verdict reached on a partial scan: proportional to the share
     * of the month that was actually looked at. Routed through
     * {@link #confidence(BigDecimal, Type, int)} for the range clamp; the trend nudge
     * is a no-op for CONSIDER, which is already a "watch it" verdict.
     */
    private BigDecimal partialConfidence(UsageFacts f, int trendDirection) {
        BigDecimal base = PARTIAL_CONFIDENCE_FLOOR.add(
                PARTIAL_CONFIDENCE_RANGE.multiply(BigDecimal.valueOf(f.scanCoverage())));
        return confidence(base, Type.CONSIDER, trendDirection);
    }

    /**
     * Nudges confidence by 0.05 when the month-over-month trend supports the
     * verdict and down by the same when it contradicts it.
     */
    private BigDecimal confidence(BigDecimal base, Type type, int trendDirection) {
        int agreement = switch (type) {
            case CANCEL, DOWNGRADE -> -trendDirection;  // falling usage supports cutting back
            case KEEP -> trendDirection;                // rising usage supports keeping
            default -> 0;                               // CONSIDER is already a "watch it" verdict
        };
        BigDecimal adjusted = base.add(bd("0.05").multiply(BigDecimal.valueOf(agreement)));
        if (adjusted.compareTo(bd("0.99")) > 0) adjusted = bd("0.99");
        if (adjusted.compareTo(bd("0.05")) < 0) adjusted = bd("0.05");
        return adjusted.setScale(2, RoundingMode.HALF_UP);
    }

    private long daysSinceLastUse(UsageFacts f) {
        if (f.lastUsedAt() == null || f.today() == null) {
            return 0;
        }
        long days = Duration.between(
                f.lastUsedAt(),
                f.today().atStartOfDay(java.time.ZoneOffset.UTC).toInstant()).toDays();
        // A snapshot written today sits slightly ahead of today's UTC midnight, which
        // would otherwise surface as a negative "days ago".
        return Math.max(0, days);
    }

    private String money(String currency, BigDecimal amount) {
        if (amount == null) return "n/a";
        String code = (currency == null || currency.isBlank()) ? "" : currency + " ";
        return code + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private String trim(String reason) {
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX - 3) + "...";
    }
}
