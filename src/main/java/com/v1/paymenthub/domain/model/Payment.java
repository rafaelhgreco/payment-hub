package com.v1.paymenthub.domain.model;

import com.v1.paymenthub.domain.exception.IllegalPaymentTransitionException;
import com.v1.paymenthub.domain.exception.InvalidMoneyException;
import com.v1.paymenthub.domain.exception.InvalidPaymentException;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

public final class Payment {
    @Getter
    private final PaymentId id;
    @Getter
    private final String provider;
    @Getter
    private final String externalId;
    @Getter
    private final Money money;
    @Getter
    private final Instant receivedAt;
    @Getter
    private PaymentStatus status;
    @Getter
    private Instant updatedAt;


    private Payment(PaymentId id, String provider, String externalId, Money money,
                    PaymentStatus status, Instant receivedAt, Instant updatedAt) {
        this.id = id;
        this.provider = provider;
        this.externalId = externalId;
        this.money = money;
        this.receivedAt = receivedAt;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public static Payment receive(String provider, String externalId, Money money, Instant now) {
        Objects.requireNonNull(money, "money is required");
        Objects.requireNonNull(now, "now is required");
        if (!money.isPositive()) {
            throw new InvalidPaymentException("Payment amount must be positive");
        }
        return new Payment(
                PaymentId.newId(),
                requireText(provider, "provider"),
                requireText(externalId, "externalId"),
                money,
                PaymentStatus.RECEIVED,
                now,
                now);
    }


    public static Payment restore(PaymentId id, String provider, String externalId, Money money,
                                  PaymentStatus status, Instant receivedAt, Instant updatedAt) {
        return new Payment(
                Objects.requireNonNull(id, "id is required"),
                requireText(provider, "provider"),
                requireText(externalId, "externalId"),
                Objects.requireNonNull(money, "money is required"),
                Objects.requireNonNull(status, "status is required"),
                Objects.requireNonNull(receivedAt, "receivedAt is required"),
                Objects.requireNonNull(updatedAt, "updatedAt is required"));
    }


    public void sendToReview(Instant now) {
        transitionTo(PaymentStatus.UNDER_REVIEW, now);
    }

    public void confirm(Instant now) {
        transitionTo(PaymentStatus.CONFIRMED, now);
    }

    public void fail(Instant now) {
        transitionTo(PaymentStatus.FAILED, now);
    }

    private void transitionTo(PaymentStatus target, Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentTransitionException(
                    "Payment %s cannot go from %s to %s".formatted(id, status, target));
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidPaymentException(field + " is required");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Payment other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }


}
