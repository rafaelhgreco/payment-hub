package com.v1.paymenthub.application.port.in;

import com.v1.paymenthub.domain.model.PaymentId;
import com.v1.paymenthub.domain.model.PaymentStatus;

public record ReceivePaymentResult(
        PaymentId paymentId,
        PaymentStatus status,
        boolean duplicate) {
}