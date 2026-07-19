package com.hartmann.application.port.`in`.saga

import com.hartmann.application.saga.event.PaymentCompleted
import com.hartmann.application.saga.event.PaymentFailed
import com.hartmann.application.saga.event.RefundCompleted
import com.hartmann.application.saga.event.ShipmentCreationFailed

interface FulfillOrderSaga {
    fun onPaymentCompleted(event: PaymentCompleted, correlationId: String)
    fun onPaymentFailed(event: PaymentFailed, correlationId: String)
    fun onShipmentCreationFailed(event: ShipmentCreationFailed, correlationId: String)
    fun onRefundCompleted(event: RefundCompleted, correlationId: String)
    fun onShipmentCreated(orderId: Long, shipmentId: Long, trackingNumber: String, correlationId: String)
    fun onShipmentInDelivery(orderId: Long, shipmentId: Long, correlationId: String)
    fun onShipmentDelivered(orderId: Long)
    fun onShipmentCancelled(orderId: Long, correlationId: String)
    fun onShipmentLost(orderId: Long, correlationId: String)
    fun onShipmentReturnedToInventory(orderId: Long, correlationId: String)
}
