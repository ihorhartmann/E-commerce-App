package com.hartmann.adapter.common.http.stripe;

import com.hartmann.domain.model.RefundStatus;

public final class StripeRefundStatusMapper {
    private StripeRefundStatusMapper() {
    }

    public static RefundStatus fromStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "pending" -> RefundStatus.PENDING;
            case "requires_action" -> RefundStatus.REQUIRES_ACTION;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> throw new IllegalArgumentException("Unknown Stripe refund status: " + stripeStatus);
        };
    }
}
