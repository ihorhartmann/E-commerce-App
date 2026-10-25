package com.hartmann.application.service.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of the failures the services themselves detect, outside the domain's rules. */
public enum ServiceErrorCode implements ErrorCode {
    REFUND_IDEMPOTENCY_KEY_REUSED("The idempotency key already belongs to a different refund"),
    CONFLICTING_REFUND_NOT_FOUND("Internal server error");

    private final String title;

    ServiceErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
