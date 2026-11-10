package com.hartmann.adapter.out.http.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.Map;

/**
 * A PaymentIntent Stripe was asked to cancel is in a status a cancellation can't leave it in - neither cancelled nor
 * paid, e.g. one waiting for a manual capture, which this service never asks for. An internal failure: the payment is
 * left as it is, for someone to look at. Not part of the gateway port: no caller tells it apart from any other
 * internal failure.
 */
public class UnexpectedPaymentIntentStatusException extends PaymentServiceException {
    public UnexpectedPaymentIntentStatusException(long orderId, String stripeStatus) {
        // String.valueOf: Map.of rejects the null of a missing status
        super(StripeGatewayErrorCode.UNEXPECTED_PAYMENT_INTENT_STATUS, "Unexpected PaymentIntent status", null,
                Map.of("orderId", orderId, "stripeStatus", String.valueOf(stripeStatus)), null);
    }
}
