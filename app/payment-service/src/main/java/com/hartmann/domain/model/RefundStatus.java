package com.hartmann.domain.model;

public enum RefundStatus {
    INITIATED,
    PENDING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
