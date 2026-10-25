package com.hartmann.adapter.common.http.stripe.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Collections;

/** Stripe reported a refund status this service doesn't know. Permanent: Stripe would report the same again. */
public class UnknownStripeRefundStatusException extends InvalidException {
    public UnknownStripeRefundStatusException(String stripeStatus) {
        // singletonMap rather than Map.of, which rejects the null of a missing status
        super(StripeErrorCode.UNKNOWN_STRIPE_REFUND_STATUS, "Unknown Stripe refund status",
                Collections.singletonMap("stripeStatus", stripeStatus));
    }
}
