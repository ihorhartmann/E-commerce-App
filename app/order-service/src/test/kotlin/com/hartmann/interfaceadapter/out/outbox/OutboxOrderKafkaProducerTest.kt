package com.hartmann.interfaceadapter.out.outbox

import com.hartmann.entities.value.Address
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import com.hartmann.usecase.port.out.outbox.OutboxMessageRepository
import com.hartmann.usecase.port.out.outbox.dto.OutboxMessage
import com.hartmann.usecase.port.common.tracing.TraceContextPropagator
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OutboxOrderKafkaProducerTest {
    private val outboxRepository = mockk<OutboxMessageRepository>(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val traceContextPropagator = mockk<TraceContextPropagator> { every { currentTraceparent() } returns null }
    private val producer = OutboxOrderKafkaProducer(outboxRepository, objectMapper, traceContextPropagator)

    private fun captureInsertedMessage(): OutboxMessage {
        val slot = slot<OutboxMessage>()
        verify(exactly = 1) { outboxRepository.insert(capture(slot)) }
        verify(exactly = 1) { traceContextPropagator.currentTraceparent() }
        return slot.captured
    }

    @Test
    fun `publishCancelReservation - inserts a CancelReservation command keyed by the order id`() {
        val orderId = 42L
        val correlationId = "corr-1"

        producer.publishCancelReservation(correlationId, orderId)

        val message = captureInsertedMessage()
        assertThrows<IllegalStateException> { message.messageId }
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, message.topic)
        assertEquals(orderId.toString(), message.key)
        assertEquals(KafkaConst.CANCEL_RESERVATION_COMMAND, message.messageType)
        assertEquals(correlationId, message.correlationId)
        assertEquals("{\"orderId\":$orderId}", message.payload)
    }

    @Test
    fun `publishCancelReservation - keeps the traceparent of the span the message is written in`() {
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        every { traceContextPropagator.currentTraceparent() } returns traceparent

        producer.publishCancelReservation("corr-8", 49L)

        assertEquals(traceparent, captureInsertedMessage().traceparent)
    }

    @Test
    fun `publishFulfillReservation - inserts a FulfillReservation command keyed by the order id`() {
        val orderId = 43L

        producer.publishFulfillReservation("corr-2", orderId)

        val message = captureInsertedMessage()
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, message.topic)
        assertEquals(orderId.toString(), message.key)
        assertEquals(KafkaConst.FULFILL_RESERVATION_COMMAND, message.messageType)
        assertEquals("{\"orderId\":$orderId}", message.payload)
    }

    @Test
    fun `publishHoldReservation - inserts a HoldReservation command keyed by the order id`() {
        val orderId = 44L

        producer.publishHoldReservation("corr-3", orderId)

        val message = captureInsertedMessage()
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, message.topic)
        assertEquals(orderId.toString(), message.key)
        assertEquals(KafkaConst.HOLD_RESERVATION_COMMAND, message.messageType)
        assertEquals("{\"orderId\":$orderId}", message.payload)
    }

    @Test
    fun `publishRestock - inserts a Restock command with the mapped items`() {
        val orderId = 45L
        val productId = 7L
        val quantity = 3

        producer.publishRestock("corr-4", orderId, listOf(RestockItemDto(productId = productId, quantity = quantity)))

        val message = captureInsertedMessage()
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, message.topic)
        assertEquals(orderId.toString(), message.key)
        assertEquals(KafkaConst.RESTOCK_COMMAND, message.messageType)
        assertEquals("{\"orderId\":$orderId,\"items\":[{\"productId\":$productId,\"quantity\":$quantity}]}", message.payload)
    }

    @Test
    fun `publishCreateShipment - inserts a CreateShipment command with the mapped address`() {
        val orderId = 46L
        val city = "Kyiv"
        val postalCode = "01001"
        val line1 = "Khreshchatyk 1"
        val line2 = ""
        val address = Address(city = city, postalCode = postalCode, line1 = line1, line2 = line2)

        producer.publishCreateShipment("corr-5", orderId, address)

        val message = captureInsertedMessage()
        assertEquals(KafkaConst.SHIPPING_COMMANDS_TOPIC, message.topic)
        assertEquals(orderId.toString(), message.key)
        assertEquals(KafkaConst.CREATE_SHIPMENT_COMMAND, message.messageType)
        assertEquals(
            "{\"orderId\":$orderId," +
                "\"address\":{\"city\":\"$city\",\"postalCode\":\"$postalCode\",\"line1\":\"$line1\",\"line2\":\"$line2\"}}",
            message.payload,
        )
    }

    @Test
    fun `publishCancelShipment - inserts a CancelShipment command keyed by the order id, like the CreateShipment one`() {
        val orderId = 48L
        val correlationId = "corr-7"

        producer.publishCancelShipment(correlationId, orderId)

        val message = captureInsertedMessage()
        assertEquals(KafkaConst.SHIPPING_COMMANDS_TOPIC, message.topic)
        assertEquals(orderId.toString(), message.key)
        assertEquals(KafkaConst.CANCEL_SHIPMENT_COMMAND, message.messageType)
        assertEquals(correlationId, message.correlationId)
        assertEquals("{\"orderId\":$orderId}", message.payload)
    }

    @Test
    fun `publishRefundRequested - inserts a RefundRequested command with the amount and currency`() {
        val orderId = 47L
        val amount = BigDecimal("40.00")
        val currency = "EUR"

        producer.publishRefundRequested("corr-6", orderId, amount, currency)

        val message = captureInsertedMessage()
        assertEquals(KafkaConst.PAYMENT_COMMANDS_TOPIC, message.topic)
        assertEquals(orderId.toString(), message.key)
        assertEquals(KafkaConst.REFUND_REQUESTED_COMMAND, message.messageType)
        assertEquals("{\"orderId\":$orderId,\"amount\":$amount,\"currency\":\"$currency\"}", message.payload)
    }
}
