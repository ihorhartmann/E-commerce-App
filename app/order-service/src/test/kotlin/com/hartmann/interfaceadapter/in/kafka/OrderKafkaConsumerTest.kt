package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderKafkaConsumerTest {

    private val saga = mockk<FulfillOrderSaga>()
    private val objectMapper = jacksonObjectMapper()
    private val consumer = OrderKafkaConsumer(saga, objectMapper)

    @Test
    fun `onPaymentEvent - dispatches PaymentCompleted to the saga`() {
        val orderId = 100L
        val correlationId = "corr-1"
        every { saga.onPaymentCompleted(orderId, correlationId) } returns Unit

        consumer.onPaymentEvent(KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onPaymentCompleted(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches PaymentFailed to the saga`() {
        val orderId = 101L
        val correlationId = "corr-2"
        every { saga.onPaymentFailed(orderId, correlationId) } returns Unit

        consumer.onPaymentEvent(KafkaConst.PAYMENT_FAILED_EVENT, correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onPaymentFailed(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches PaymentExpired to the saga`() {
        val orderId = 106L
        val correlationId = "corr-7"
        every { saga.onPaymentExpired(orderId, correlationId) } returns Unit

        consumer.onPaymentEvent(KafkaConst.PAYMENT_EXPIRED_EVENT, correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onPaymentExpired(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches RefundCompleted to the saga`() {
        val orderId = 102L
        val correlationId = "corr-3"
        val amount = Money(BigDecimal("40.00"), Currency.USD)
        every { saga.onRefundCompleted(orderId, amount, correlationId) } returns Unit

        consumer.onPaymentEvent(
            KafkaConst.REFUND_COMPLETED_EVENT, correlationId,
            "{\"orderId\":$orderId,\"amount\":40.00,\"currency\":\"USD\"}",
        )

        verify(exactly = 1) { saga.onRefundCompleted(orderId, amount, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - dispatches RefundFailed to the saga`() {
        val orderId = 103L
        val correlationId = "corr-4"
        every { saga.onRefundFailed(orderId, correlationId) } returns Unit

        consumer.onPaymentEvent(KafkaConst.REFUND_FAILED_EVENT, correlationId, "{\"orderId\":$orderId}")

        verify(exactly = 1) { saga.onRefundFailed(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onPaymentEvent - ignores unrecognized message types`() {
        consumer.onPaymentEvent("SomeUnrelatedEvent", "corr-5", "{}")

        verify { saga wasNot Called }
    }

    @Test
    fun `onPaymentEvent - throws without dispatching when the payload is malformed JSON`() {
        assertThrows<Exception> {
            consumer.onPaymentEvent(KafkaConst.PAYMENT_COMPLETED_EVENT, "corr-6", "not json")
        }

        verify { saga wasNot Called }
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentCreated to the saga with the tracking number`() {
        val orderId = 201L
        val correlationId = "corr-8"
        val trackingNumber = "TRACK-1"
        every { saga.onShipmentCreated(orderId, trackingNumber, correlationId) } returns Unit

        consumer.onShippingEvent(
            KafkaConst.SHIPMENT_CREATED_EVENT, correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"$trackingNumber\"}",
        )

        verify(exactly = 1) { saga.onShipmentCreated(orderId, trackingNumber, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentInDelivery to the saga`() {
        val orderId = 202L
        val correlationId = "corr-9"
        every { saga.onShipmentInDelivery(orderId, correlationId) } returns Unit

        consumer.onShippingEvent(
            KafkaConst.SHIPMENT_IN_DELIVERY_EVENT, correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentInDelivery(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentDelivered to the saga without the correlation id`() {
        val orderId = 203L
        every { saga.onShipmentDelivered(orderId) } returns Unit

        consumer.onShippingEvent(
            KafkaConst.SHIPMENT_DELIVERED_EVENT, "corr-10",
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentDelivered(orderId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentCancelled to the saga`() {
        val orderId = 204L
        val correlationId = "corr-11"
        every { saga.onShipmentCancelled(orderId, correlationId) } returns Unit

        consumer.onShippingEvent(
            KafkaConst.SHIPMENT_CANCELLED_EVENT, correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentCancelled(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentLost to the saga`() {
        val orderId = 205L
        val correlationId = "corr-12"
        every { saga.onShipmentLost(orderId, correlationId) } returns Unit

        consumer.onShippingEvent(
            KafkaConst.SHIPMENT_LOST_EVENT, correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentLost(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - dispatches ShipmentReturnedToInventory to the saga`() {
        val orderId = 206L
        val correlationId = "corr-13"
        every { saga.onShipmentReturnedToInventory(orderId, correlationId) } returns Unit

        consumer.onShippingEvent(
            KafkaConst.SHIPMENT_RETURNED_TO_INVENTORY_EVENT, correlationId,
            "{\"orderId\":$orderId,\"trackingNumber\":\"TRACK-1\"}",
        )

        verify(exactly = 1) { saga.onShipmentReturnedToInventory(orderId, correlationId) }
        confirmVerified(saga)
    }

    @Test
    fun `onShippingEvent - ignores unrecognized message types`() {
        consumer.onShippingEvent("SomeUnrelatedEvent", "corr-14", "{}")

        verify { saga wasNot Called }
    }

    @Test
    fun `onShippingEvent - throws without dispatching when the payload is malformed JSON`() {
        assertThrows<Exception> {
            consumer.onShippingEvent(KafkaConst.SHIPMENT_CREATED_EVENT, "corr-15", "not json")
        }

        verify { saga wasNot Called }
    }
}
