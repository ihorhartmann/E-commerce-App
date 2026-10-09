package com.hartmann.usecase.port.`in`.saga

import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata

interface FulfillOrderSaga {
    fun onPaymentCompleted(message: KafkaMessageMetadata, orderId: Long)
    fun onPaymentFailed(message: KafkaMessageMetadata, orderId: Long)
    fun onPaymentExpired(message: KafkaMessageMetadata, orderId: Long)
    fun onRefundCompleted(message: KafkaMessageMetadata, orderId: Long, amount: Money)
    fun onRefundFailed(message: KafkaMessageMetadata, orderId: Long)
    fun onShipmentCreated(message: KafkaMessageMetadata, orderId: Long, shipmentId: Long)
    fun onShipmentInDelivery(message: KafkaMessageMetadata, orderId: Long)
    fun onShipmentDelivered(message: KafkaMessageMetadata, orderId: Long)
    fun onShipmentCancelled(message: KafkaMessageMetadata, orderId: Long)
    fun onShipmentLost(message: KafkaMessageMetadata, orderId: Long)
    fun onShipmentReturnedToInventory(message: KafkaMessageMetadata, orderId: Long)
}
