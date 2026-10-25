package com.hartmann.adapter.common.http.stripe.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of what this service can't make of the data Stripe sends it. */
public enum StripeErrorCode implements ErrorCode {
    UNKNOWN_STRIPE_REFUND_STATUS("Unknown Stripe refund status");

    private final String title;

    StripeErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
