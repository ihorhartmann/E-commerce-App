package com.hartmann.interfaceadapter.out.kafka

import com.hartmann.entities.value.Address
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Header
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderKafkaProducerImplTest {
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val producer = OrderKafkaProducerImpl(kafkaTemplate, objectMapper)

    private fun headerValue(record: ProducerRecord<String, String>, key: String): String? {
        val header: Header? = record.headers().lastHeader(key)
        return header?.let { String(it.value(), StandardCharsets.UTF_8) }
    }

    private fun captureSentRecord(): ProducerRecord<String, String> {
        val slot = slot<ProducerRecord<String, String>>()
        verify(exactly = 1) { kafkaTemplate.send(capture(slot)) }
        return slot.captured
    }

    @Test
    fun `publishCancelReservation - sends a CancelReservation command keyed by the order id`() {
        val orderId = 42L

        producer.publishCancelReservation("corr-1", orderId)

        val record = captureSentRecord()
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, record.topic())
        assertEquals(orderId.toString(), record.key())
        assertEquals(KafkaConst.CANCEL_RESERVATION_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        assertEquals("corr-1", headerValue(record, KafkaConst.CORRELATION_ID))
        assertEquals("{\"orderId\":$orderId}", record.value())
    }

    @Test
    fun `publishFulfillReservation - sends a FulfillReservation command keyed by the order id`() {
        val orderId = 43L

        producer.publishFulfillReservation("corr-2", orderId)

        val record = captureSentRecord()
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, record.topic())
        assertEquals(orderId.toString(), record.key())
        assertEquals(KafkaConst.FULFILL_RESERVATION_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        assertEquals("{\"orderId\":$orderId}", record.value())
    }

    @Test
    fun `publishHoldReservation - sends a HoldReservation command keyed by the order id`() {
        val orderId = 44L

        producer.publishHoldReservation("corr-3", orderId)

        val record = captureSentRecord()
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, record.topic())
        assertEquals(orderId.toString(), record.key())
        assertEquals(KafkaConst.HOLD_RESERVATION_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        assertEquals("{\"orderId\":$orderId}", record.value())
    }

    @Test
    fun `publishRestock - sends a Restock command with the mapped items`() {
        val orderId = 45L

        producer.publishRestock("corr-4", orderId, listOf(RestockItemDto(productId = 7, quantity = 3)))

        val record = captureSentRecord()
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, record.topic())
        assertEquals(orderId.toString(), record.key())
        assertEquals(KafkaConst.RESTOCK_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        assertEquals("{\"orderId\":$orderId,\"items\":[{\"productId\":7,\"quantity\":3}]}", record.value())
    }

    @Test
    fun `publishCreateShipment - sends a CreateShipment command with the mapped address`() {
        val orderId = 46L
        val address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

        producer.publishCreateShipment("corr-5", orderId, address)

        val record = captureSentRecord()
        assertEquals(KafkaConst.SHIPPING_COMMANDS_TOPIC, record.topic())
        assertEquals(orderId.toString(), record.key())
        assertEquals(KafkaConst.CREATE_SHIPMENT_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        assertEquals(
            "{\"orderId\":$orderId,\"address\":{\"city\":\"Kyiv\",\"postalCode\":\"01001\",\"line1\":\"Khreshchatyk 1\",\"line2\":\"\"}}",
            record.value(),
        )
    }

    @Test
    fun `publishRefundRequested - sends a RefundRequested command with the amount and currency`() {
        val orderId = 47L
        val amount = BigDecimal("40.00")

        producer.publishRefundRequested("corr-6", orderId, amount, "USD")

        val record = captureSentRecord()
        assertEquals(KafkaConst.PAYMENT_COMMANDS_TOPIC, record.topic())
        assertEquals(orderId.toString(), record.key())
        assertEquals(KafkaConst.REFUND_REQUESTED_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        assertEquals("{\"orderId\":$orderId,\"amount\":40.00,\"currency\":\"USD\"}", record.value())
    }
}
