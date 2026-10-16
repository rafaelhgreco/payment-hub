package com.v1.paymenthub.application.port.in;

public interface ReceivePaymentUseCase {

    ReceivePaymentResult execute(ReceivePaymentCommand command);
}