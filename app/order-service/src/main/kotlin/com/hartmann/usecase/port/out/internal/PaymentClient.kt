package com.hartmann.usecase.port.out.internal

import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import com.hartmann.entities.value.Money

interface PaymentClient {
    fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult

    /**
     * Asks payment-service to cancel the order's payment - the customer cancels the order before paying it. Returns once
     * it is cancelled; payment-service then tells this service (PaymentCancelled), which is what cancels the order.
     * Asking again for a payment already cancelled succeeds. Fails if the payment is already confirmed.
     */
    fun cancelPayment(correlationId: String, orderId: Long)
}
