package com.hartmann.e2e.support.api.payment

import java.math.BigDecimal

data class PaymentResponse(
    val id: Long,
    val orderId: Long,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

data class RefundResponse(
    val id: Long,
    val paymentId: Long,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val createdAt: String,
)
