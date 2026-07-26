package com.hartmann.adapter.in.http.refund.dto;

import com.hartmann.domain.model.Refund;

import java.math.BigDecimal;

public record RefundResponse(
        long id,
        long paymentId,
        BigDecimal amount,
        String currency,
        String status,
        String createdAt
) {
    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.id(),
                refund.paymentId(),
                refund.amount().amount(),
                refund.amount().currency().name(),
                refund.status().name(),
                refund.createdAt().toString()
        );
    }
}
