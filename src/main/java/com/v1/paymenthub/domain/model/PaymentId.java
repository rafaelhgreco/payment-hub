package com.v1.paymenthub.domain.model;

import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {
    public PaymentId {
        Objects.requireNonNull(value, "value is required");
    }

    public static PaymentId newId() {
        return new PaymentId(UUID.randomUUID());
    }
    @Override
    public @NonNull String toString() {
        return value.toString();
    }
}
