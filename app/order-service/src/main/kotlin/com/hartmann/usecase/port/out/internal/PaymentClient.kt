package com.hartmann.usecase.port.out.internal

import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import com.hartmann.entities.value.Money

interface PaymentClient {
    fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult
}
