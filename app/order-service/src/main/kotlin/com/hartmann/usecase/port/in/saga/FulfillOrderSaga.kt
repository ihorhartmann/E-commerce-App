package com.hartmann.usecase.port.`in`.saga

import com.hartmann.usecase.port.`in`.saga.event.PaymentCompletedEvent
import com.hartmann.usecase.port.`in`.saga.event.PaymentFailedEvent
import com.hartmann.usecase.port.`in`.saga.event.RefundCompletedEvent
import com.hartmann.usecase.port.`in`.saga.event.RefundFailedEvent
import com.hartmann.usecase.port.`in`.saga.event.ShipmentCreationFailedEvent

interface FulfillOrderSaga {
    fun onPaymentCompleted(event: PaymentCompletedEvent, correlationId: String)
    fun onPaymentFailed(event: PaymentFailedEvent, correlationId: String)
    fun onShipmentCreationFailed(event: ShipmentCreationFailedEvent, correlationId: String)
    fun onRefundCompleted(event: RefundCompletedEvent, correlationId: String)
    fun onRefundFailed(event: RefundFailedEvent, correlationId: String)
    fun onShipmentCreated(orderId: Long, trackingNumber: String, correlationId: String)
    fun onShipmentInDelivery(orderId: Long, correlationId: String)
    fun onShipmentDelivered(orderId: Long)
    fun onShipmentCancelled(orderId: Long, correlationId: String)
    fun onShipmentLost(orderId: Long, correlationId: String)
    fun onShipmentReturnedToInventory(orderId: Long, correlationId: String)
}
