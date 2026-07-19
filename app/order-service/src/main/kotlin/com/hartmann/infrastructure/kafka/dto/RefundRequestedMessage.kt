package com.hartmann.infrastructure.kafka.dto

import java.math.BigDecimal

data class RefundRequestedMessage(
    val orderId: Long,
    val amount: BigDecimal,
    val currency: String,
)
