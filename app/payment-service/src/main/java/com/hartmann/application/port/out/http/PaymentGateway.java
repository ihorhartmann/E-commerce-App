package com.hartmann.application.port.out.http;

import com.hartmann.application.port.out.http.dto.PaymentCancellationReason;
import com.hartmann.application.port.out.http.dto.PaymentIntentState;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.value.Money;

public interface PaymentGateway {
    SubmitPaymentResult submitPayment(long orderId, Money amount);

    /**
     * Cancels the PaymentIntent identified by externalId - orderId's payment - so it can no longer be paid. Cancelling
     * one that already is cancelled succeeds, so a repeated call after a failure is safe. Returns what the PaymentIntent
     * is after the call: cancelled, or - when the customer paid it before it could be cancelled - succeeded or still
     * processing.
     */
    PaymentIntentState cancelPayment(long orderId, String externalId, PaymentCancellationReason reason);

    /**
     * Submits refund against the payment identified by paymentExternalId. Submitting the same refund
     * again (same idempotency key) returns the gateway's original result instead of refunding twice.
     */
    SubmitRefundResult submitRefund(String paymentExternalId, Refund refund);
}
