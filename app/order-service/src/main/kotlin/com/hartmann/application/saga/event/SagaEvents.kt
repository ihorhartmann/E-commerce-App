package com.hartmann.application.saga.event

import java.math.BigDecimal

data class PaymentCompleted(val orderId: Long)
data class PaymentFailed(val orderId: Long)
data class ShipmentCreationFailed(val orderId: Long)
data class RefundCompleted(val orderId: Long, val amount: BigDecimal, val currency: String)
data class RefundFailed(val orderId: Long)
