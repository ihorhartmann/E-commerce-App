package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.exception.OrderServiceException
import com.hartmann.entities.exception.attributesOf
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.`in`.kafka.dto.OrderIdEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.RefundCompletedEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.ShipmentCancelledEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.ShipmentStatusChangedEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.fromShipmentCancelledEvent
import com.hartmann.interfaceadapter.`in`.kafka.exception.InvalidMessageHeaderException
import com.hartmann.interfaceadapter.`in`.kafka.exception.MalformedPayloadException
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

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
        handleMessage(KafkaConst.PAYMENT_EVENTS_TOPIC, messageType, messageId, correlationId) { message ->
            when (messageType) {
                KafkaConst.PAYMENT_COMPLETED_EVENT -> {
                    saga.onPaymentCompleted(message, parse<OrderIdEvent>(messageType, payload).orderId)
                    true
                }

                KafkaConst.PAYMENT_FAILED_EVENT -> {
                    saga.onPaymentFailed(message, parse<OrderIdEvent>(messageType, payload).orderId)
                    true
                }

                KafkaConst.PAYMENT_EXPIRED_EVENT -> {
                    saga.onPaymentExpired(message, parse<OrderIdEvent>(messageType, payload).orderId)
                    true
                }

                KafkaConst.REFUND_COMPLETED_EVENT -> {
                    val event = parse<RefundCompletedEvent>(messageType, payload)
                    saga.onRefundCompleted(message, event.orderId, Money(event.amount, Currency.fromCode(event.currency)))
                    true
                }

                KafkaConst.REFUND_FAILED_EVENT -> {
                    saga.onRefundFailed(message, parse<OrderIdEvent>(messageType, payload).orderId)
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
        handleMessage(KafkaConst.SHIPPING_EVENTS_TOPIC, messageType, messageId, correlationId) { message ->
            when (messageType) {
                KafkaConst.SHIPMENT_CREATED_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(messageType, payload)
                    saga.onShipmentCreated(message, event.orderId, event.shipmentId)
                    true
                }

                KafkaConst.SHIPMENT_IN_DELIVERY_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(messageType, payload)
                    saga.onShipmentInDelivery(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_DELIVERED_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(messageType, payload)
                    saga.onShipmentDelivered(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_CANCELLED_EVENT -> {
                    val event = parse<ShipmentCancelledEvent>(messageType, payload)
                    saga.onShipmentCancelled(message, event.orderId, fromShipmentCancelledEvent(event))
                    true
                }

                KafkaConst.SHIPMENT_LOST_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(messageType, payload)
                    saga.onShipmentLost(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_RETURNED_TO_INVENTORY_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(messageType, payload)
                    saga.onShipmentReturnedToInventory(message, event.orderId)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Centralizes the receive/dispatch/success logging and the failure logging shared by every listener in
     * this class, so each `@KafkaListener` method only has to describe its own message-type routing. A
     * failure is rethrown as it is: the error handler (KafkaErrorHandlerConfig) retries it or dead-letters it
     * at once, decided by [isPermanentFailure].
     */
    private fun handleMessage(
        topic: String,
        messageType: String,
        messageId: String,
        correlationId: String,
        dispatch: (KafkaMessageMetadata) -> Boolean,
    ) {
        LOG.debug("received {} messageType={} correlationId={}", topic, messageType, correlationId)

        val handled = try {
            dispatch(KafkaMessageMetadata(parseMessageId(messageId), correlationId, messageType))
        } catch (e: Exception) {
            logFailure(topic, messageType, correlationId, e)
            throw e
        }

        if (!handled) {
            LOG.info("ignoring unhandled {} messageType={} correlationId={}", topic, messageType, correlationId)
            return
        }

        LOG.info("successfully processed {} messageType={} correlationId={}", topic, messageType, correlationId)
    }

    private fun parseMessageId(messageId: String): UUID =
        try {
            UUID.fromString(messageId)
        } catch (e: IllegalArgumentException) {
            throw InvalidMessageHeaderException(KafkaConst.MESSAGE_ID, messageId, e)
        }

    private inline fun <reified T> parse(messageType: String, payload: String): T =
        try {
            objectMapper.readValue(payload, T::class.java)
        } catch (e: JacksonException) {
            throw MalformedPayloadException(messageType, e)
        }

    // Logged on every attempt, as the attempt is made. A permanent failure as a warning, since the
    // message is only dead-lettered; anything else as an error with its stack trace.
    private fun logFailure(topic: String, messageType: String, correlationId: String, exception: Exception) {
        val event = if (isPermanentFailure(exception)) {
            LOG.atWarn().setMessage("Message rejected")
        } else {
            LOG.atError().setCause(exception).setMessage("Message handling failed")
        }

        event.addKeyValue("topic", topic)
            .addKeyValue("messageType", messageType)
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("exceptionMessage", exception.message)
        generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<OrderServiceException>()
            .firstOrNull()
            ?.let { event.addKeyValue("code", it.code.code) }
        attributesOf(exception).forEach { (key, value) -> event.addKeyValue(key, value) }
        event.log()
    }
}
