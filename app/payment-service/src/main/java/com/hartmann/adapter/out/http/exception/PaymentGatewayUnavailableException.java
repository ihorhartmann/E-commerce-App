package com.hartmann.adapter.out.http.exception;

import com.hartmann.application.exception.ApplicationException;

/** Stripe itself is temporarily unable to serve the request (rate limited, unreachable, or having
 *  an incident) - unlike PaymentGatewayException, retrying later is expected to succeed. */
public class PaymentGatewayUnavailableException extends ApplicationException {
    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
