package com.v1.paymenthub.domain.exception;

public class IllegalPaymentTransitionException extends RuntimeException {
    public IllegalPaymentTransitionException(String message) {
        super(message);
    }
}
