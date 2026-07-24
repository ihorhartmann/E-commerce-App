package com.hartmann.usecase.port.out.http

import com.hartmann.usecase.port.out.http.dto.SubmitPaymentResult
import com.hartmann.entities.value.Money

interface PaymentClient {
    fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult
}
