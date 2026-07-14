package com.hartmann.adapter.out.http.mapper;

import com.hartmann.application.port.out.http.exception.PaymentGatewayException;
import com.hartmann.domain.model.RefundStatus;

public final class StripeRefundStatusMapper {
    private StripeRefundStatusMapper() {
    }

    public static RefundStatus toDomain(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "pending" -> RefundStatus.PENDING;
            case "requires_action" -> RefundStatus.REQUIRES_ACTION;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> throw new PaymentGatewayException("Unknown Stripe refund status: " + stripeStatus);
        };
    }
}
