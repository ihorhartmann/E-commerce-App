package com.hartmann.application.port.out.http.dto;

/** Why a payment's PaymentIntent is cancelled. */
public enum PaymentCancellationReason {
    // the customer cancelled the order before paying it
    REQUESTED_BY_CUSTOMER,
    // nobody paid it in time - the expiration sweep
    EXPIRED
}
