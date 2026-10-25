package com.hartmann.application.port.out.http.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of the failures of calls to the payment gateway. */
public enum GatewayErrorCode implements ErrorCode {
    PAYMENT_GATEWAY_REJECTED("The payment gateway rejected the request"),
    PAYMENT_GATEWAY_UNAVAILABLE("The payment gateway is temporarily unavailable");

    private final String title;

    GatewayErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
