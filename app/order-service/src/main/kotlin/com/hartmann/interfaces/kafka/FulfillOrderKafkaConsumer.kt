package com.hartmann.interfaces.kafka

import com.hartmann.application.port.`in`.saga.FulfillOrderSaga
import com.hartmann.application.saga.event.*
import org.springframework.stereotype.Component

/** Temporary consumer entry points; a real Kafka listener will delegate to these methods later. */
@Component
class FulfillOrderKafkaConsumer(private val saga: FulfillOrderSaga) {
    fun consume(event: StockReserved) = saga.onStockReserved(event)
    fun consume(event: StockRejected) = saga.onStockRejected(event)
    fun consume(event: PaymentCompleted) = saga.onPaymentCompleted(event)
    fun consume(event: PaymentFailed) = saga.onPaymentFailed(event)
    fun consume(event: ShipmentCreated) = saga.onShipmentCreated(event)
    fun consume(event: ShipmentFailed) = saga.onShipmentFailed(event)
}
