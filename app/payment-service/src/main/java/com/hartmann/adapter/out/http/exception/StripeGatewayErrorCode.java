package com.hartmann.adapter.out.http.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of the failures of calls to Stripe that are this service's own fault, not the request's. */
public enum StripeGatewayErrorCode implements ErrorCode {
    PAYMENT_GATEWAY_AUTHENTICATION_FAILED("Internal server error");

    private final String title;

    StripeGatewayErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
