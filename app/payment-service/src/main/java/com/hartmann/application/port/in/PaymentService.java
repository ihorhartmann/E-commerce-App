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
     * Cancels orderId's payment, for a customer who cancels the order before paying it: first its PaymentIntent at the
     * gateway, so it can no longer be paid, then the payment itself, telling order-service (PaymentCancelled) - which
     * cancels the order. A payment that is already cancelled, expired or failed is returned as it is: a repeated
     * request, or the order's cancellation is under way already.
     * <p>
     * Throws PaymentAlreadyConfirmedException for a paid payment - also one only the gateway knew to be paid, which is
     * then confirmed here, telling order-service (PaymentCompleted) - and PaymentProcessingException while the gateway
     * is still processing the payment.
     */
    Payment cancelPayment(String correlationId, long orderId);

    /**
     * Gives up on every open payment (INITIATED/PENDING/ATTEMPT_DECLINED) whose expiresAt has passed: cancels its
     * PaymentIntent at the gateway, then expires it, telling order-service (PaymentExpired) about the ones it doesn't
     * already know the outcome of. One the customer paid at the last moment is confirmed instead (PaymentCompleted), and
     * one still being processed - or one that fails - is left for the next run.
     */
    void expireOverduePayments();
}
