package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.exception.DomainException
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.`in`.kafka.dto.OrderIdEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.RefundCompletedEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.ShipmentCancelledEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.ShipmentStatusChangedEvent
import com.hartmann.interfaceadapter.`in`.kafka.dto.fromShipmentCancelledEvent
import com.hartmann.interfaceadapter.`in`.kafka.exception.InvalidMessageHeaderException
import com.hartmann.interfaceadapter.`in`.kafka.exception.NonRetryableException
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
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

        // These fail the same way on every attempt, so they're wrapped in NonRetryableException and go
        // straight to the DLT. Anything else (database errors, ...) is transient and retried.
        private val NON_RETRYABLE_EXCEPTIONS = listOf(
            JacksonException::class, // payload is not valid JSON / doesn't match the event
            // The domain model rejects an invalid state transition or invalid value: DomainException
            // for a disallowed order transition or unsupported currency, IllegalStateException /
            // IllegalArgumentException from its check()/require() guards.
            DomainException::class,
            IllegalStateException::class,
            IllegalArgumentException::class,
            OrderNotFoundException::class, // the order the event refers to doesn't exist
            InvalidMessageHeaderException::class, // a header is present but unusable (messageId isn't a UUID)
        )
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
        handleMessage(KafkaConst.SHIPPING_EVENTS_TOPIC, messageType, messageId, correlationId) { message ->
            when (messageType) {
                KafkaConst.SHIPMENT_CREATED_EVENT -> {
                    val event = objectMapper.readValue(payload, ShipmentStatusChangedEvent::class.java)
                    saga.onShipmentCreated(message, event.orderId, event.shipmentId)
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
                    val event = objectMapper.readValue(payload, ShipmentCancelledEvent::class.java)
                    saga.onShipmentCancelled(message, event.orderId, fromShipmentCancelledEvent(event))
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
     * Centralizes the receive/dispatch/success logging and the failure classification shared by every
     * listener in this class, so each `@KafkaListener` method only has to describe its own message-type
     * routing.
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
            throw classify(e)
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
            throw InvalidMessageHeaderException("messageId $messageId is not a UUID", e)
        }

    /**
     * Wraps e in NonRetryableException if it, or any exception in its cause chain, is one of
     * NON_RETRYABLE_EXCEPTIONS, and returns it unchanged otherwise.
     */
    private fun classify(e: Exception): Exception {
        val nonRetryable = generateSequence<Throwable>(e) { it.cause }
            .any { cause -> NON_RETRYABLE_EXCEPTIONS.any { it.isInstance(cause) } }

        return if (nonRetryable) NonRetryableException(e) else e
    }
}
