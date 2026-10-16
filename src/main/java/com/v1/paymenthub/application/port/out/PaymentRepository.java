package com.v1.paymenthub.application.port.out;

import com.v1.paymenthub.domain.model.Payment;

import java.util.Optional;

public interface PaymentRepository {

    Optional<Payment> findByProviderAndExternalId(String provider, String externalId);

    void save(Payment payment);
}