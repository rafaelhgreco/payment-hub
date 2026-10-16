package com.v1.paymenthub.domain.policy;

import com.v1.paymenthub.domain.model.Money;

import java.util.Objects;

public class PaymentReviewPolicy {
    private final Money threshold;

    public PaymentReviewPolicy(Money threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold is required");
    }
    public boolean requiresReview(Money amount) {
        if (!amount.currency().equals(threshold.currency())) {
            return true;
        }
        return amount.isGreaterThan(threshold);
    }

}
