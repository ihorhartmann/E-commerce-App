package com.hartmann.application.port.out.http

import com.hartmann.application.port.out.http.dto.SubmitPaymentResult
import com.hartmann.domain.value.Money

interface PaymentClient {
    fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult
}
