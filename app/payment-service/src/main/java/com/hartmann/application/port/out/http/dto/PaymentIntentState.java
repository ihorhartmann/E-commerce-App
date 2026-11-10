package com.hartmann.application.port.out.http.dto;

/** What a PaymentIntent is after an attempt to cancel it: cancelled, or paid before it could be. */
public enum PaymentIntentState {
    CANCELLED,
    SUCCEEDED,
    // paid, but the money is still on its way - neither cancelled nor succeeded yet
    PROCESSING
}
