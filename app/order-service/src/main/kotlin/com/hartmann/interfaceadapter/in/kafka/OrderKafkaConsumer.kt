package com.hartmann.interfaceadapter.`in`.kafka

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
import org.slf4j.MDC
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
        @Header(KafkaConst.MESSAGE_TYPE, required = false) messageType: String?,
        @Header(KafkaConst.MESSAGE_ID, required = false) messageId: String?,
        @Header(KafkaConst.CORRELATION_ID, required = false) correlationId: String?,
        @Payload payload: String,
    ) {
        handleMessage(KafkaConst.PAYMENT_EVENTS_TOPIC, messageType, messageId, correlationId) { message ->
            when (message.messageType) {
                KafkaConst.PAYMENT_COMPLETED_EVENT -> {
                    saga.onPaymentCompleted(message, parse<OrderIdEvent>(message.messageType, payload).orderId)
                    true
                }

                KafkaConst.PAYMENT_FAILED_EVENT -> {
                    saga.onPaymentFailed(message, parse<OrderIdEvent>(message.messageType, payload).orderId)
                    true
                }

                KafkaConst.PAYMENT_EXPIRED_EVENT -> {
                    saga.onPaymentExpired(message, parse<OrderIdEvent>(message.messageType, payload).orderId)
                    true
                }

                KafkaConst.PAYMENT_CANCELLED_EVENT -> {
                    saga.onPaymentCancelled(message, parse<OrderIdEvent>(message.messageType, payload).orderId)
                    true
                }

                KafkaConst.REFUND_COMPLETED_EVENT -> {
                    val event = parse<RefundCompletedEvent>(message.messageType, payload)
                    saga.onRefundCompleted(message, event.orderId, Money(event.amount, Currency.fromCode(event.currency)))
                    true
                }

                KafkaConst.REFUND_FAILED_EVENT -> {
                    saga.onRefundFailed(message, parse<OrderIdEvent>(message.messageType, payload).orderId)
                    true
                }

                else -> false
            }
        }
    }

    @KafkaListener(topics = [KafkaConst.SHIPPING_EVENTS_TOPIC], groupId = "\${spring.kafka.consumer.group-id}")
    fun onShippingEvent(
        @Header(KafkaConst.MESSAGE_TYPE, required = false) messageType: String?,
        @Header(KafkaConst.MESSAGE_ID, required = false) messageId: String?,
        @Header(KafkaConst.CORRELATION_ID, required = false) correlationId: String?,
        @Payload payload: String,
    ) {
        handleMessage(KafkaConst.SHIPPING_EVENTS_TOPIC, messageType, messageId, correlationId) { message ->
            when (message.messageType) {
                KafkaConst.SHIPMENT_CREATED_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(message.messageType, payload)
                    saga.onShipmentCreated(message, event.orderId, event.shipmentId)
                    true
                }

                KafkaConst.SHIPMENT_IN_DELIVERY_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(message.messageType, payload)
                    saga.onShipmentInDelivery(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_DELIVERED_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(message.messageType, payload)
                    saga.onShipmentDelivered(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_CANCELLED_EVENT -> {
                    val event = parse<ShipmentCancelledEvent>(message.messageType, payload)
                    saga.onShipmentCancelled(message, event.orderId, fromShipmentCancelledEvent(event))
                    true
                }

                KafkaConst.SHIPMENT_LOST_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(message.messageType, payload)
                    saga.onShipmentLost(message, event.orderId)
                    true
                }

                KafkaConst.SHIPMENT_RETURNED_TO_INVENTORY_EVENT -> {
                    val event = parse<ShipmentStatusChangedEvent>(message.messageType, payload)
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
     *
     * The message's ids are in the MDC while it is handled, so every line logged meanwhile - here, in the saga, in the
     * use cases - carries them as fields of its own. The headers are checked here rather than by `@Header` binding,
     * so a message missing one is rejected like any other invalid message - logged, with our code, and dead-lettered.
     */
    private fun handleMessage(
        topic: String,
        messageType: String?,
        messageId: String?,
        correlationId: String?,
        dispatch: (KafkaMessageMetadata) -> Boolean,
    ) {
        val headers = mapOf(
            KafkaConst.MESSAGE_TYPE to messageType,
            KafkaConst.MESSAGE_ID to messageId,
            KafkaConst.CORRELATION_ID to correlationId,
        )
        headers.forEach { (header, value) -> value?.let { MDC.put(header, it) } }
        try {
            LOG.atDebug().setMessage("received kafka message").addKeyValue("topic", topic).log()

            val handled = try {
                // in the order the Go services check them in
                val type = requireHeader(KafkaConst.MESSAGE_TYPE, messageType)
                val id = parseMessageId(requireHeader(KafkaConst.MESSAGE_ID, messageId))
                val correlation = requireHeader(KafkaConst.CORRELATION_ID, correlationId)
                dispatch(KafkaMessageMetadata(id, correlation, type))
            } catch (e: Exception) {
                logFailure(topic, e)
                throw e
            }

            if (!handled) {
                LOG.atInfo().setMessage("ignoring unhandled kafka message").addKeyValue("topic", topic).log()
                return
            }

            LOG.atInfo().setMessage("successfully processed kafka message").addKeyValue("topic", topic).log()
        } finally {
            headers.keys.forEach(MDC::remove)
        }
    }

    private fun requireHeader(header: String, value: String?): String = value ?: throw InvalidMessageHeaderException(header)

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

    // Logged on every attempt, as the attempt is made, as a warning: a permanent failure is rejected - dead-lettered at
    // once - a transient one retried, with its stack trace. The error is logged once, when the message is dead-lettered
    // (RetryableDeadLetterPublishingRecoverer).
    private fun logFailure(topic: String, exception: Exception) {
        val event = if (isPermanentFailure(exception)) {
            LOG.atWarn().setMessage("kafka message rejected")
        } else {
            LOG.atWarn().setCause(exception).setMessage("kafka message handling failed")
        }

        event.addKeyValue("topic", topic)
        failureKeyValues(exception).forEach { (key, value) -> event.addKeyValue(key, value) }
        event.log()
    }
}
