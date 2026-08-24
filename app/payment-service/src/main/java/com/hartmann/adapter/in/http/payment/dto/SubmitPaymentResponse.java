package com.hartmann.adapter.in.http.payment.dto;

import com.hartmann.application.port.in.dto.PaymentIntentResult;

public record SubmitPaymentResponse(
        long paymentId,
        long orderId,
        String externalId,
        String clientSecret,
        String status
) {
    public static SubmitPaymentResponse from(PaymentIntentResult result) {
        var payment = result.payment();
        return new SubmitPaymentResponse(
                payment.id(),
                payment.orderId(),
                payment.externalId(),
                result.clientSecret(),
                payment.status().name()
        );
    }
}
