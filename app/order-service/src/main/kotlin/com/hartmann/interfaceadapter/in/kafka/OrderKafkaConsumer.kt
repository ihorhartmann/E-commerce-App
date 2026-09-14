package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.`in`.kafka.dto.OrderIdEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.RefundCompletedEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.ShipmentStatusChangedEvent
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class OrderKafkaConsumer(
    private val saga: FulfillOrderSaga,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(OrderKafkaConsumer::class.java)
    }

    @KafkaListener(topics = [KafkaConst.PAYMENT_EVENTS_TOPIC], groupId = "\${spring.kafka.consumer.group-id}")
    fun onPaymentEvent(
        @Header(KafkaConst.MESSAGE_TYPE) messageType: String,
        @Header(KafkaConst.MESSAGE_ID) messageId: String,
        @Header(KafkaConst.CORRELATION_ID) correlationId: String,
        @Payload payload: String,
    ) {
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        handleMessage(KafkaConst.PAYMENT_EVENTS_TOPIC, messageType, correlationId) {
            when (messageType) {
                KafkaConst.PAYMENT_COMPLETED_EVENT -> {
                    saga.onPaymentCompleted(message, objectMapper.readValue(payload, OrderIdEvent::class.java).orderId)
                    true
                }

                KafkaConst.PAYMENT_FAILED_EVENT -> {
                    saga.onPaymentFailed(message, objectMapper.readValue(payload, OrderIdEvent::class.java).orderId)
                    true
                }

                KafkaConst.PAYMENT_EXPIRED_EVENT -> {
                    saga.onPaymentExpired(message, objectMapper.readValue(payload, OrderIdEvent::class.java).orderId)
                    true
                }

                KafkaConst.REFUND_COMPLETED_EVENT -> {
                    val event = objectMapper.readValue(payload, RefundCompletedEvent::class.java)
                    saga.onRefundCompleted(message, event.orderId, Money(event.amount, Currency.fromCode(event.currency)))
                    true
                }

                KafkaConst.REFUND_FAILED_EVENT -> {
                    saga.onRefundFailed(message, objectMapper.readValue(payload, OrderIdEvent::class.java).orderId)
                    true
                }

                else -> false
            }
        }
    }

    @KafkaListener(topics = [KafkaConst.SHIPPING_EVENTS_TOPIC], groupId = "\${spring.kafka.consumer.group-id}")
    fun onShippingEvent(
        @Header(KafkaConst.MESSAGE_TYPE) messageType: String,
        @Header(KafkaConst.MESSAGE_ID) messageId: String,
        @Header(KafkaConst.CORRELATION_ID) correlationId: String,
        @Payload payload: String,
    ) {
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        handleMessage(KafkaConst.SHIPPING_EVENTS_TOPIC, messageType, correlationId) {
            when (messageType) {
                KafkaConst.SHIPMENT_CREATED_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentCreated(message, event.orderId, event.trackingNumber)
                    true
                }

                KafkaConst.SHIPMENT_IN_DELIVERY_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentInDelivery(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_DELIVERED_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentDelivered(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_CANCELLED_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentCancelled(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_LOST_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentLost(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_RETURNED_TO_INVENTORY_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentReturnedToInventory(message, event.orderId)
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
