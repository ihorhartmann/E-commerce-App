package com.hartmann.usecase.port.`in`.saga

import com.hartmann.entities.value.Money

interface FulfillOrderSaga {
    fun onPaymentCompleted(orderId: Long, correlationId: String)
    fun onPaymentFailed(orderId: Long, correlationId: String)
    fun onShipmentCreationFailed(orderId: Long, correlationId: String)
    fun onRefundCompleted(orderId: Long, amount: Money, correlationId: String)
    fun onRefundFailed(orderId: Long, correlationId: String)
    fun onShipmentCreated(orderId: Long, trackingNumber: String, correlationId: String)
    fun onShipmentInDelivery(orderId: Long, correlationId: String)
    fun onShipmentDelivered(orderId: Long)
    fun onShipmentCancelled(orderId: Long, correlationId: String)
    fun onShipmentLost(orderId: Long, correlationId: String)
    fun onShipmentReturnedToInventory(orderId: Long, correlationId: String)
}
