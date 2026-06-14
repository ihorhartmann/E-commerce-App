package com.hartmann.adapter.in.http.payment;

import com.hartmann.domain.model.Payment;

public record PaymentResponse(
        long id,
        long orderId,
        double amount,
        String currency,
        String status,
        String createdAt,
        String updatedAt
) {
    public static PaymentResponse fromDomainModel(Payment payment) {
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
