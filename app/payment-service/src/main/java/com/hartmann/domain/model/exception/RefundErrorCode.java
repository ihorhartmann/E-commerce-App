package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of the rules the refund itself enforces. */
public enum RefundErrorCode implements ErrorCode {
    REFUND_TRANSITION_NOT_ALLOWED("Refund can't make this transition in its current status"),
    INCONSISTENT_REFUND("Internal server error"),
    REFUND_INVARIANT_VIOLATED("Internal server error");

    private final String title;

    RefundErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
