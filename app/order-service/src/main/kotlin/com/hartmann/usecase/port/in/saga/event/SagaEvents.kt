package com.hartmann.usecase.port.`in`.saga.event

import java.math.BigDecimal

data class PaymentCompletedEvent(val orderId: Long)
data class PaymentFailedEvent(val orderId: Long)
data class ShipmentCreationFailedEvent(val orderId: Long)
data class RefundCompletedEvent(val orderId: Long, val amount: BigDecimal, val currency: String)
data class RefundFailedEvent(val orderId: Long)
