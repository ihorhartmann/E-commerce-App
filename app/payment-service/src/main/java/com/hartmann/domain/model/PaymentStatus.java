package com.hartmann.domain.model;

import java.util.List;

public enum PaymentStatus {
    INITIATED,
    PENDING,
    CONFIRMED,
    ATTEMPT_DECLINED,
    FAILED,
    EXPIRED,
    // the customer cancelled the order before paying it
    CANCELLED;

    public static final List<PaymentStatus> NON_TERMINAL_STATUSES = List.of(INITIATED, PENDING, ATTEMPT_DECLINED);
}
