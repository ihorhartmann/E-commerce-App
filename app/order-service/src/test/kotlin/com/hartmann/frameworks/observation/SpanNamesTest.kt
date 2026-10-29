package com.hartmann.frameworks.observation

import com.hartmann.interfaceadapter.common.tracing.KafkaTracingAttributes
import com.hartmann.usecase.port.common.kafka.KafkaConst
import io.micrometer.common.KeyValue
import org.apache.kafka.common.header.internals.RecordHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.charset.StandardCharsets

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SpanNamesTest {

    @Test
    fun `httpSpanName - joins the method and the route template`() {
        assertEquals("GET /api/v1/orders/{orderId}", httpSpanName("GET", "/api/v1/orders/{orderId}"))
    }

    @Test
    fun `httpSpanName - drops the scheme and host of a client's base URL`() {
        assertEquals("POST /api/v1/payments", httpSpanName("POST", "http://payment-service:3003/api/v1/payments"))
    }

    @Test
    fun `httpSpanName - is the method alone when no route is known`() {
        assertEquals("POST", httpSpanName("POST", null))
    }

    @Test
    fun `messagingSpanName - joins the operation, the topic and the message type`() {
        assertEquals("send payment.commands RefundRequested", messagingSpanName("send", "payment.commands", "RefundRequested"))
    }

    @Test
    fun `messagingSpanName - is the operation and the topic alone without a message type`() {
        assertEquals("process payment.events", messagingSpanName("process", "payment.events", null))
    }

    @Test
    fun `headerValue - is the value of the last header with the key`() {
        val key = "messageType"
        val headers = RecordHeaders()
            .add(key, "First".toByteArray(StandardCharsets.UTF_8))
            .add(key, "Last".toByteArray(StandardCharsets.UTF_8))

        assertEquals("Last", headerValue(headers, key))
    }

    @Test
    fun `headerValue - is null without a header with the key`() {
        assertNull(headerValue(RecordHeaders(), "messageType"))
    }

    @Test
    fun `messageKeyValues - tags the type, id and correlation id as they arrived`() {
        val messageType = "SomethingUnknown"
        val messageId = "not-a-uuid"
        val correlationId = "not-a-real-id"
        val headers = RecordHeaders()
            .add(KafkaConst.MESSAGE_TYPE, messageType.toByteArray(StandardCharsets.UTF_8))
            .add(KafkaConst.MESSAGE_ID, messageId.toByteArray(StandardCharsets.UTF_8))
            .add(KafkaConst.CORRELATION_ID, correlationId.toByteArray(StandardCharsets.UTF_8))

        val actual = messageKeyValues(headers).toList()

        assertTrue(actual.contains(KeyValue.of(KafkaTracingAttributes.MESSAGE_TYPE, messageType)))
        assertTrue(actual.contains(KeyValue.of(KafkaTracingAttributes.MESSAGE_ID, messageId)))
        assertTrue(actual.contains(KeyValue.of(KafkaTracingAttributes.CONVERSATION_ID, correlationId)))
    }

    @Test
    fun `messageKeyValues - adds nothing for a missing header`() {
        assertTrue(messageKeyValues(RecordHeaders()).toList().isEmpty())
    }
}
