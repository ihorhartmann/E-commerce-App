package com.hartmann.adapter.in.http.payment.dto;

import com.hartmann.domain.model.Payment;

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
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.id(),
                payment.orderId(),
                payment.amount().amount(),
                payment.amount().currency().name(),
                payment.status().name(),
                payment.createdAt().toString(),
                payment.updatedAt().toString()
        );
    }
}
