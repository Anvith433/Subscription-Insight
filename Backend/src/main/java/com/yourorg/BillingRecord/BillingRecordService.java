package com.yourorg.BillingRecord;

import java.time.Instant;
import java.time.YearMonth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yourorg.Subscriptions.Renewal_Cycle;
import com.yourorg.Subscriptions.Subscription;
import com.yourorg.common.DataSource;

@Service
public class BillingRecordService {

    private static final Logger log = LoggerFactory.getLogger(BillingRecordService.class);

    @Autowired
    private BillingRecordRepository billingRecordRepository;

    public BillingRecord save(BillingRecord billingRecord) {
        return billingRecordRepository.save(billingRecord);
    }

    /**
     * Records the charge for one subscription in one billing period, if there is a
     * charge to record.
     *
     * <p>The previous version set only subscription, amount and period, leaving
     * currency, paid_at, payment_method, source and created_at null -- all five are
     * NOT NULL, so every call would have failed. It was also never invoked, so the
     * failure was invisible.
     *
     * <p>Idempotent: guarded by an existence check and by the
     * uk_billing_subscription_period unique key.
     *
     * @param period billing month in YYYY-MM form
     * @return the record created, or null when this subscription is not billed in
     *         this period
     */
    @Transactional
    public BillingRecord createBill(Subscription sub, String period) {
        if (!isBilledIn(sub, period)) {
            return null;
        }
        if (billingRecordRepository.existsBySubscriptionIdAndBillingPeriod(sub.getId(), period)) {
            return null;
        }

        BillingRecord bill = new BillingRecord();
        bill.setSubscription(sub);
        bill.setAmount(sub.getPrice());
        bill.setCurrency(sub.getCurrency());
        bill.setBillingPeriod(period);
        bill.setPaidAt(Instant.now());
        // The charge is inferred from the subscription record rather than observed
        // from a payment provider, so it is attributed to the system, not to a
        // specific instrument the user confirmed.
        bill.setPaymentMethod(PaymentMethod.AUTO_SYNC);
        bill.setSource(DataSource.SYSTEM);
        bill.setCreatedAt(Instant.now());

        log.debug("Generated bill for subscription {} in {}", sub.getId(), period);
        return billingRecordRepository.save(bill);
    }

    /**
     * A monthly plan is charged every month; a yearly plan only in its renewal
     * month; a free plan never. Generating a full monthly charge for a yearly plan
     * would have overstated spend twelvefold.
     */
    private boolean isBilledIn(Subscription sub, String period) {
        if (sub.getRenewalCycle() == null || sub.getRenewalCycle() == Renewal_Cycle.FREE) {
            return false;
        }
        if (sub.getPrice() == null || sub.getPrice().signum() <= 0) {
            return false;
        }
        if (sub.getRenewalCycle() == Renewal_Cycle.YEARLY) {
            return sub.getRenewalDate() != null
                    && YearMonth.from(sub.getRenewalDate()).toString().equals(period);
        }
        return true;
    }
}
