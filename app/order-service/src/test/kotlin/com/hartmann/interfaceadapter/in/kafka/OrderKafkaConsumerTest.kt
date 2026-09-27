package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.`in`.kafka.exception.InvalidMessageHeaderException
import com.hartmann.interfaceadapter.`in`.kafka.exception.NonRetryableException
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderKafkaConsumerTest {

    private val saga = mockk<FulfillOrderSaga>()
    private val objectMapper = jacksonObjectMapper()
    private val consumer = OrderKafkaConsumer(saga, objectMapper)

    @Test
    fun `onPaymentEvent - dispatches PaymentCompleted to the saga`() {
        val orderId = 100L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-1"
        val messageType = KafkaConst.PAYMENT_COMPLETED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onPaymentCompleted(message, orderId) } returns Unit

        consumer.onPaymentEvent(messageType, messageId.toString(), correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onPaymentCompleted(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches PaymentFailed to the saga`() {
        val orderId = 101L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-2"
        val messageType = KafkaConst.PAYMENT_FAILED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onPaymentFailed(message, orderId) } returns Unit

        consumer.onPaymentEvent(messageType, messageId.toString(), correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onPaymentFailed(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches PaymentExpired to the saga`() {
        val orderId = 106L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-7"
        val messageType = KafkaConst.PAYMENT_EXPIRED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onPaymentExpired(message, orderId) } returns Unit

        consumer.onPaymentEvent(messageType, messageId.toString(), correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onPaymentExpired(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches RefundCompleted to the saga`() {
        val orderId = 102L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-3"
        val messageType = KafkaConst.REFUND_COMPLETED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        val amount = Money(BigDecimal("40.00"), Currency.USD)
        every { saga.onRefundCompleted(message, orderId, amount) } returns Unit

        consumer.onPaymentEvent(
            messageType, messageId.toString(), correlationId,
            "{\"orderId\":$orderId,\"amount\":40.00,\"currency\":\"USD\"}",
        )

        verify(exactly = 1) { saga.onRefundCompleted(message, orderId, amount) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches RefundFailed to the saga`() {
        val orderId = 103L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-4"
        val messageType = KafkaConst.REFUND_FAILED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onRefundFailed(message, orderId) } returns Unit

        consumer.onPaymentEvent(messageType, messageId.toString(), correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onRefundFailed(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - ignores unrecognized message types`() {
        consumer.onPaymentEvent("SomeUnrelatedEvent", UUID.randomUUID().toString(), "corr-5", "{}")

        verify { saga wasNot Called }
    }

    @Test
    fun `onPaymentEvent - throws a NonRetryableException without dispatching when the payload is malformed JSON`() {
        val exception = assertThrows<NonRetryableException> {
            consumer.onPaymentEvent(KafkaConst.PAYMENT_COMPLETED_EVENT, UUID.randomUUID().toString(), "corr-6", "not json")
        }

        assertTrue(exception.cause is JacksonException)
        verify { saga wasNot Called }
    }

    @Test
    fun `onPaymentEvent - throws a NonRetryableException without dispatching when the messageId is not a UUID`() {
        val exception = assertThrows<NonRetryableException> {
            consumer.onPaymentEvent(KafkaConst.PAYMENT_COMPLETED_EVENT, "not-a-uuid", "corr-18", "{\"orderId\":108}")
        }

        assertTrue(exception.cause is InvalidMessageHeaderException)
        verify { saga wasNot Called }
    }

    @Test
    fun `onPaymentEvent - wraps a non-retryable saga failure in a NonRetryableException`() {
        val orderId = 107L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-16"
        val messageType = KafkaConst.PAYMENT_COMPLETED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        val cause = OrderNotFoundException(orderId)
        every { saga.onPaymentCompleted(message, orderId) } throws cause

        val exception = assertThrows<NonRetryableException> {
            consumer.onPaymentEvent(messageType, messageId.toString(), correlationId, "{\"orderId\":$orderId}")
        }

        assertSame(cause, exception.cause)
        verify(exactly = 1) { saga.onPaymentCompleted(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - rethrows a retryable saga failure unwrapped`() {
        val orderId = 108L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-17"
        val messageType = KafkaConst.PAYMENT_COMPLETED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        val expected = RuntimeException("database unavailable")
        every { saga.onPaymentCompleted(message, orderId) } throws expected

        val actual = assertThrows<RuntimeException> {
            consumer.onPaymentEvent(messageType, messageId.toString(), correlationId, "{\"orderId\":$orderId}")
        }

        assertSame(expected, actual)
        verify(exactly = 1) { saga.onPaymentCompleted(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentCreated to the saga with the tracking number`() {
        val orderId = 201L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-8"
        val messageType = KafkaConst.SHIPMENT_CREATED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        val trackingNumber = "TRACK-1"
        every { saga.onShipmentCreated(message, orderId, trackingNumber) } returns Unit

        consumer.onShippingEvent(
            messageType, messageId.toString(), correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"$trackingNumber\"}",
        )

        verify(exactly = 1) { saga.onShipmentCreated(message, orderId, trackingNumber) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentInDelivery to the saga`() {
        val orderId = 202L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-9"
        val messageType = KafkaConst.SHIPMENT_IN_DELIVERY_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onShipmentInDelivery(message, orderId) } returns Unit

        consumer.onShippingEvent(
            messageType, messageId.toString(), correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentInDelivery(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentDelivered to the saga`() {
        val orderId = 203L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-10"
        val messageType = KafkaConst.SHIPMENT_DELIVERED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onShipmentDelivered(message, orderId) } returns Unit

        consumer.onShippingEvent(
            messageType, messageId.toString(), correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentDelivered(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentCancelled to the saga`() {
        val orderId = 204L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-11"
        val messageType = KafkaConst.SHIPMENT_CANCELLED_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onShipmentCancelled(message, orderId) } returns Unit

        consumer.onShippingEvent(
            messageType, messageId.toString(), correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentCancelled(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentLost to the saga`() {
        val orderId = 205L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-12"
        val messageType = KafkaConst.SHIPMENT_LOST_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onShipmentLost(message, orderId) } returns Unit

        consumer.onShippingEvent(
            messageType, messageId.toString(), correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentLost(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentReturnedToInventory to the saga`() {
        val orderId = 206L
        val messageId = UUID.randomUUID()
        val correlationId = "corr-13"
        val messageType = KafkaConst.SHIPMENT_RETURNED_TO_INVENTORY_EVENT
        val message = KafkaMessageMetadata(messageId, correlationId, messageType)
        every { saga.onShipmentReturnedToInventory(message, orderId) } returns Unit

        consumer.onShippingEvent(
            messageType, messageId.toString(), correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentReturnedToInventory(message, orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - ignores unrecognized message types`() {
        consumer.onShippingEvent("SomeUnrelatedEvent", UUID.randomUUID().toString(), "corr-14", "{}")

        verify { saga wasNot Called }
    }

    @Test
    fun `onShippingEvent - throws a NonRetryableException without dispatching when the payload is malformed JSON`() {
        val exception = assertThrows<NonRetryableException> {
            consumer.onShippingEvent(KafkaConst.SHIPMENT_CREATED_EVENT, UUID.randomUUID().toString(), "corr-15", "not json")
        }

        assertTrue(exception.cause is JacksonException)
        verify { saga wasNot Called }
    }

    @Test
    fun `onShippingEvent - throws a NonRetryableException without dispatching when the messageId is not a UUID`() {
        val exception = assertThrows<NonRetryableException> {
            consumer.onShippingEvent(KafkaConst.SHIPMENT_CREATED_EVENT, "not-a-uuid", "corr-19", "{\"orderId\":109}")
        }

        assertTrue(exception.cause is InvalidMessageHeaderException)
        verify { saga wasNot Called }
    }
}
