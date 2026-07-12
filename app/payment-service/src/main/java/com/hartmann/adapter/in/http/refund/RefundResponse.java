package com.hartmann.adapter.in.http.refund;

import java.math.BigDecimal;

public record RefundResponse(
        long id,
        long paymentId,
        BigDecimal amount,
        String currency,
        String status,
        String createdAt
) {
}
