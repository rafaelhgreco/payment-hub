package com.v1.paymenthub.application.port.out;

import com.v1.paymenthub.domain.event.PaymentProcessedEvent;

public interface PaymentEventPublisher {

    void publish(PaymentProcessedEvent event);
}