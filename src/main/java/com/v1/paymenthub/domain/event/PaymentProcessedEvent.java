package com.v1.paymenthub.domain.event;

import com.v1.paymenthub.domain.model.Money;
import com.v1.paymenthub.domain.model.Payment;
import com.v1.paymenthub.domain.model.PaymentId;
import com.v1.paymenthub.domain.model.PaymentStatus;

import java.time.Instant;

public record PaymentProcessedEvent(
        PaymentId paymentId,
        String provider,
        String externalId,
        Money money,
        PaymentStatus status,
        Instant occurredAt) {

    public static PaymentProcessedEvent from(Payment payment) {
        return new PaymentProcessedEvent(
                payment.getId(),
                payment.getProvider(),
                payment.getExternalId(),
                payment.getMoney(),
                payment.getStatus(),
                payment.getUpdatedAt());
    }
}