package com.v1.paymenthub.domain.model;

public enum PaymentStatus {
    RECEIVED,
    UNDER_REVIEW,
    CONFIRMED,
    FAILED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case RECEIVED -> target == UNDER_REVIEW || target == CONFIRMED || target == FAILED;
            case UNDER_REVIEW -> target == CONFIRMED || target == FAILED;
            case CONFIRMED, FAILED -> false;
        };
    }
}
