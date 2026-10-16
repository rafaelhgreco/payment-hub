package com.v1.paymenthub.application.port.in;

import java.math.BigDecimal;

public record ReceivePaymentCommand(
        String provider,
        String externalId,
        BigDecimal amount,
        String currency) {
}