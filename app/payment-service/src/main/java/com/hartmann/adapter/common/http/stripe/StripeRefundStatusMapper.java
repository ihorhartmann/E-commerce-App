package com.hartmann.adapter.common.http.stripe;

import com.hartmann.domain.model.RefundStatus;

public final class StripeRefundStatusMapper {
    private StripeRefundStatusMapper() {
    }

    public static RefundStatus fromStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            // requires_action needs customer follow-up we don't act on - treated the same as
            // pending until a later refund.updated resolves it to succeeded or failed.
            case "pending", "requires_action" -> RefundStatus.PENDING;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> throw new IllegalArgumentException("Unknown Stripe refund status: " + stripeStatus);
        };
    }
}
