package com.hartmann.application.port.out.repository.exception;

import com.hartmann.domain.exception.ErrorCode;

public enum RepositoryErrorCode implements ErrorCode {
    PAYMENT_NOT_FOUND("Payment not found"),
    REFUND_NOT_FOUND("Refund not found"),
    CONCURRENT_UPDATE("The resource was changed concurrently, try again"),
    DUPLICATE_REFUND_IDEMPOTENCY_KEY("A refund with this idempotency key already exists");

    private final String title;

    RepositoryErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
