package com.hartmann.interfaceadapter.`in`.kafka.dto

import java.math.BigDecimal

data class RefundCompletedEvent(val orderId: Long, val amount: BigDecimal, val currency: String)
