package com.hartmann.adapter.in.http.refund;

import com.hartmann.domain.model.Refund;

public record RefundResponse(
        long id,
        long paymentId,
        double amount,
        String currency,
        String status,
        String createdAt
) {
    public static RefundResponse fromDomainModel(Refund refund) {
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
