package com.hartmann.adapter.in.http.payment.dto;

public record SubmitPaymentResponse(
        long paymentId,
        long orderId,
        String externalId,
        String clientSecret,
        String status
) {
}
