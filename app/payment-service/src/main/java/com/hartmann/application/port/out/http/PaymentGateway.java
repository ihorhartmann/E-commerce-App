package com.hartmann.application.port.out.http;

import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.value.Money;

public interface PaymentGateway {
    SubmitPaymentResult submitPayment(long orderId, Money amount);

    /**
     * Submits refund against the payment identified by paymentExternalId. Submitting the same refund
     * again (same idempotency key) returns the gateway's original result instead of refunding twice.
     */
    SubmitRefundResult submitRefund(String paymentExternalId, Refund refund);
}
