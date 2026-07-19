package com.hartmann.interfaces.kafka

import com.hartmann.application.kafka.KafkaConst
import com.hartmann.application.port.`in`.saga.FulfillOrderSaga
import com.hartmann.application.saga.event.PaymentCompleted
import com.hartmann.application.saga.event.PaymentFailed
import com.hartmann.application.saga.event.RefundCompleted
import com.hartmann.application.saga.event.RefundFailed
import com.hartmann.application.saga.event.ShipmentCreationFailed
import com.hartmann.interfaces.kafka.dto.ShipmentEventMessage
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class FulfillOrderKafkaConsumer(
    private val saga: FulfillOrderSaga,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(FulfillOrderKafkaConsumer::class.java)
    }

    @KafkaListener(topics = [KafkaConst.PAYMENT_EVENTS], groupId = "\${spring.kafka.consumer.group-id}")
    fun onPaymentEvent(
        @Header(KafkaConst.EVENT_TYPE) eventType: String,
        @Header(KafkaConst.CORRELATION_ID) correlationId: String,
        @Payload payload: String,
    ) {
        handleEvent(KafkaConst.PAYMENT_EVENTS, eventType, correlationId) {
            when (eventType) {
                KafkaConst.PAYMENT_COMPLETED_EVENT_TYPE -> {
                    saga.onPaymentCompleted(objectMapper.readValue(payload, PaymentCompleted::class.java), correlationId)
                    true
                }

                KafkaConst.PAYMENT_FAILED_EVENT_TYPE -> {
                    saga.onPaymentFailed(objectMapper.readValue(payload, PaymentFailed::class.java), correlationId)
                    true
                }

                KafkaConst.REFUND_COMPLETED_EVENT_TYPE -> {
                    saga.onRefundCompleted(objectMapper.readValue(payload, RefundCompleted::class.java), correlationId)
                    true
                }

                KafkaConst.REFUND_FAILED_EVENT_TYPE -> {
                    saga.onRefundFailed(objectMapper.readValue(payload, RefundFailed::class.java), correlationId)
                    true
                }

                else -> false
            }
        }
    }

    @KafkaListener(topics = [KafkaConst.SHIPPING_EVENTS], groupId = "\${spring.kafka.consumer.group-id}")
    fun onShippingEvent(
        @Header(KafkaConst.EVENT_TYPE) eventType: String,
        @Header(KafkaConst.CORRELATION_ID) correlationId: String,
        @Payload payload: String,
    ) {
        handleEvent(KafkaConst.SHIPPING_EVENTS, eventType, correlationId) {
            when (eventType) {
                KafkaConst.SHIPMENT_CREATION_FAILED_EVENT_TYPE -> {
                    saga.onShipmentCreationFailed(
                        objectMapper.readValue(payload, ShipmentCreationFailed::class.java),
                        correlationId,
                    )
                    true
                }

                KafkaConst.SHIPMENT_CREATED_EVENT_TYPE -> {
                    val message = objectMapper.readValue(payload, ShipmentEventMessage::class.java)
                    saga.onShipmentCreated(message.orderId, message.trackingNumber, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_IN_DELIVERY_EVENT_TYPE -> {
                    val message = objectMapper.readValue(payload, ShipmentEventMessage::class.java)
                    saga.onShipmentInDelivery(message.orderId, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_DELIVERED_EVENT_TYPE -> {
                    val message = objectMapper.readValue(payload, ShipmentEventMessage::class.java)
                    saga.onShipmentDelivered(message.orderId)
                    true
                }

                KafkaConst.SHIPMENT_CANCELLED_EVENT_TYPE -> {
                    val message = objectMapper.readValue(payload, ShipmentEventMessage::class.java)
                    saga.onShipmentCancelled(message.orderId, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_LOST_EVENT_TYPE -> {
                    val message = objectMapper.readValue(payload, ShipmentEventMessage::class.java)
                    saga.onShipmentLost(message.orderId, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_RETURNED_TO_INVENTORY_EVENT_TYPE -> {
                    val message = objectMapper.readValue(payload, ShipmentEventMessage::class.java)
                    saga.onShipmentReturnedToInventory(message.orderId, correlationId)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Centralizes the receive/dispatch/success logging shared by every listener in this class,
     * so each `@KafkaListener` method only has to describe its own event-type routing.
     */
    private fun handleEvent(topic: String, eventType: String, correlationId: String, dispatch: () -> Boolean) {
        LOG.debug("received {} eventType={} correlationId={}", topic, eventType, correlationId)

        if (!dispatch()) {
            LOG.info("ignoring unhandled {} eventType={} correlationId={}", topic, eventType, correlationId)
            return
        }

        LOG.info("successfully processed {} eventType={} correlationId={}", topic, eventType, correlationId)
    }
}
