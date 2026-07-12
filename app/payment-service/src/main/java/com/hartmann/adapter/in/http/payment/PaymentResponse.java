package com.hartmann.adapter.in.http.payment;

import java.math.BigDecimal;

public record PaymentResponse(
        long id,
        long orderId,
        BigDecimal amount,
        String currency,
        String status,
        String createdAt,
        String updatedAt
) {
}
