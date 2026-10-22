package com.hartmann.interfaceadapter.out.http.payment.dto

import java.math.BigDecimal

data class SubmitPaymentRequest(val orderId: Long, val amount: BigDecimal, val currency: String)

data class SubmitPaymentResponse(
    val paymentId: Long,
    val orderId: Long,
    val externalId: String,
    val clientSecret: String,
    val status: String,
)
