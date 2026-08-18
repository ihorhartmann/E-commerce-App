package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.`in`.kafka.dto.OrderIdEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.RefundCompletedEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.ShipmentStatusChangedEvent
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
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
        @Header(KafkaConst.CORRELATION_ID) correlationId: String,
        @Payload payload: String,
    ) {
        handleMessage(KafkaConst.PAYMENT_EVENTS_TOPIC, messageType, correlationId) {
            when (messageType) {
                KafkaConst.PAYMENT_COMPLETED_EVENT -> {
                    saga.onPaymentCompleted(
                        objectMapper.readValue(payload, OrderIdEvent::class.java).orderId,
                        correlationId
                    )
                    true
                }

                KafkaConst.PAYMENT_FAILED_EVENT -> {
                    saga.onPaymentFailed(
                        objectMapper.readValue(payload, OrderIdEvent::class.java).orderId,
                        correlationId
                    )
                    true
                }

                KafkaConst.REFUND_COMPLETED_EVENT -> {
                    val event = objectMapper.readValue(payload, RefundCompletedEvent::class.java)
                    saga.onRefundCompleted(
                        event.orderId,
                        Money(event.amount, Currency.fromCode(event.currency)),
                        correlationId
                    )
                    true
                }

                KafkaConst.REFUND_FAILED_EVENT -> {
                    saga.onRefundFailed(
                        objectMapper.readValue(payload, OrderIdEvent::class.java).orderId,
                        correlationId
                    )
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
                        objectMapper.readValue(payload, OrderIdEvent::class.java).orderId,
                        correlationId
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
