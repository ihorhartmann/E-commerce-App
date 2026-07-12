package com.hartmann.application.saga.event

sealed interface SagaEvent { val orderId: Long }

data class OrderPlaced(override val orderId: Long) : SagaEvent
data class StockReserved(override val orderId: Long, val reservationId: Long) : SagaEvent
data class StockRejected(override val orderId: Long) : SagaEvent
data class PaymentRequested(override val orderId: Long, val reservationId: Long) : SagaEvent
data class PaymentCompleted(override val orderId: Long, val paymentId: Long) : SagaEvent
data class PaymentFailed(override val orderId: Long) : SagaEvent
data class ShipmentRequested(override val orderId: Long, val paymentId: Long) : SagaEvent
data class ShipmentCreated(override val orderId: Long, val shipmentId: Long) : SagaEvent
data class ShipmentFailed(override val orderId: Long) : SagaEvent
data class RefundRequested(override val orderId: Long, val paymentId: Long) : SagaEvent
data class OrderCancelled(override val orderId: Long) : SagaEvent
