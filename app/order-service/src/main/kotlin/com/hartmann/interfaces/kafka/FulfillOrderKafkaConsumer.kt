package com.hartmann.interfaces.kafka

import com.hartmann.application.kafka.KafkaConst
import com.hartmann.application.port.`in`.saga.FulfillOrderSaga
import com.hartmann.application.saga.event.PaymentCompletedEvent
import com.hartmann.application.saga.event.PaymentFailedEvent
import com.hartmann.application.saga.event.RefundCompletedEvent
import com.hartmann.application.saga.event.RefundFailedEvent
import com.hartmann.application.saga.event.ShipmentCreationFailedEvent
import com.hartmann.interfaces.kafka.dto.ShipmentStatusChangedEvent
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

    @KafkaListener(topics = [KafkaConst.PAYMENT_EVENTS_TOPIC], groupId = "\${spring.kafka.consumer.group-id}")
    fun onPaymentEvent(
        @Header(KafkaConst.MESSAGE_TYPE) messageType: String,
        @Header(KafkaConst.CORRELATION_ID) correlationId: String,
        @Payload payload: String,
    ) {
        handleMessage(KafkaConst.PAYMENT_EVENTS_TOPIC, messageType, correlationId) {
            when (messageType) {
                KafkaConst.PAYMENT_COMPLETED_EVENT -> {
                    saga.onPaymentCompleted(objectMapper.readValue(payload, PaymentCompletedEvent::class.java), correlationId)
                    true
                }

                KafkaConst.PAYMENT_FAILED_EVENT -> {
                    saga.onPaymentFailed(objectMapper.readValue(payload, PaymentFailedEvent::class.java), correlationId)
                    true
                }

                KafkaConst.REFUND_COMPLETED_EVENT -> {
                    saga.onRefundCompleted(objectMapper.readValue(payload, RefundCompletedEvent::class.java), correlationId)
                    true
                }

                KafkaConst.REFUND_FAILED_EVENT -> {
                    saga.onRefundFailed(objectMapper.readValue(payload, RefundFailedEvent::class.java), correlationId)
                    true
                }

                else -> false
            }
        }
    }

    @KafkaListener(topics = [KafkaConst.SHIPPING_EVENTS_TOPIC], groupId = "\${spring.kafka.consumer.group-id}")
    fun onShippingEvent(
        @Header(KafkaConst.MESSAGE_TYPE) messageType: String,
        @Header(KafkaConst.CORRELATION_ID) correlationId: String,
        @Payload payload: String,
    ) {
        handleMessage(KafkaConst.SHIPPING_EVENTS_TOPIC, messageType, correlationId) {
            when (messageType) {
                KafkaConst.SHIPMENT_CREATION_FAILED_EVENT -> {
                    saga.onShipmentCreationFailed(
                        objectMapper.readValue(payload, ShipmentCreationFailedEvent::class.java),
                        correlationId,
                    )
                    true
                }

                KafkaConst.SHIPMENT_CREATED_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentCreated(event.orderId, event.trackingNumber, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_IN_DELIVERY_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentInDelivery(event.orderId, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_DELIVERED_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentDelivered(event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_CANCELLED_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentCancelled(event.orderId, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_LOST_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentLost(event.orderId, correlationId)
                    true
                }

                KafkaConst.SHIPMENT_RETURNED_TO_INVENTORY_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentReturnedToInventory(event.orderId, correlationId)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Centralizes the receive/dispatch/success logging shared by every listener in this class,
     * so each `@KafkaListener` method only has to describe its own message-type routing.
     */
    private fun handleMessage(topic: String, messageType: String, correlationId: String, dispatch: () -> Boolean) {
        LOG.debug("received {} messageType={} correlationId={}", topic, messageType, correlationId)

        if (!dispatch()) {
            LOG.info("ignoring unhandled {} messageType={} correlationId={}", topic, messageType, correlationId)
            return
        }

        LOG.info("successfully processed {} messageType={} correlationId={}", topic, messageType, correlationId)
    }
}
