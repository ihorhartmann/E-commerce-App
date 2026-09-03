package com.hartmann.application.port.in;

import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Money;

public interface PaymentService {
    Payment getByOrderId(long orderId);

    PaymentIntentResult submitPayment(long orderId, Money amount);

    Payment updateStatusByExternalId(String externalId, PaymentStatus status);

    /**
     * Moves every non-terminal payment (INITIATED/PENDING/ATTEMPT_DECLINED) whose expiresAt has
     * passed to EXPIRED, notifying order-service (via Kafka) about the ones it doesn't already
     * know the outcome of.
     */
    void expireOverduePayments();
}
