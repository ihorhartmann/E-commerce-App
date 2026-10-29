package com.hartmann.frameworks.observation

import com.hartmann.interfaceadapter.common.tracing.KafkaTracingAttributes
import com.hartmann.usecase.port.common.kafka.KafkaConst
import io.micrometer.common.KeyValue
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext
import java.nio.charset.StandardCharsets

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KafkaSpanNameConventionsTest {

    private fun headers(vararg headers: Pair<String, String>) = RecordHeaders().apply {
        headers.forEach { (key, value) -> add(key, value.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun senderContext(topic: String, headers: RecordHeaders) =
        KafkaRecordSenderContext(ProducerRecord(topic, null, "1", "{}", headers), "kafkaTemplate") { "cluster" }

    private fun receiverContext(topic: String, headers: RecordHeaders) =
        KafkaRecordReceiverContext(
            ConsumerRecord(topic, 0, 0L, "1", "{}").also { record -> headers.forEach { record.headers().add(it) } },
            "listener",
        ) { "cluster" }

    @Test
    fun `getContextualName - names a sent message send, its topic and its type`() {
        val topic = KafkaConst.PAYMENT_COMMANDS_TOPIC
        val context = senderContext(topic, headers(KafkaConst.MESSAGE_TYPE to KafkaConst.REFUND_REQUESTED_COMMAND))

        assertEquals("send $topic ${KafkaConst.REFUND_REQUESTED_COMMAND}", KafkaTemplateSpanNameConvention().getContextualName(context))
    }

    // which headers become which attributes, as they arrived, is messageKeyValues' (SpanNamesTest) - these only check
    // that the conventions add them
    @Test
    fun `getHighCardinalityKeyValues - tags a sent message with its correlation id`() {
        val correlationId = "42"
        val context = senderContext(KafkaConst.PAYMENT_COMMANDS_TOPIC, headers(KafkaConst.CORRELATION_ID to correlationId))

        val actual = KafkaTemplateSpanNameConvention().getHighCardinalityKeyValues(context).toList()

        assertTrue(actual.contains(KeyValue.of(KafkaTracingAttributes.CONVERSATION_ID, correlationId)))
    }

    @Test
    fun `getContextualName - names a handled message process, its topic and its type as it arrived, even an unknown one`() {
        val topic = KafkaConst.PAYMENT_EVENTS_TOPIC
        val messageType = "SomethingUnknown"
        val context = receiverContext(topic, headers(KafkaConst.MESSAGE_TYPE to messageType))

        assertEquals("process $topic $messageType", KafkaListenerSpanNameConvention().getContextualName(context))
    }

    @Test
    fun `getContextualName - names a handled message without a type header process and its topic alone`() {
        val topic = KafkaConst.PAYMENT_EVENTS_TOPIC

        assertEquals("process $topic", KafkaListenerSpanNameConvention().getContextualName(receiverContext(topic, headers())))
    }

    @Test
    fun `getHighCardinalityKeyValues - tags a handled message with its correlation id`() {
        val correlationId = "43"
        val context = receiverContext(KafkaConst.PAYMENT_EVENTS_TOPIC, headers(KafkaConst.CORRELATION_ID to correlationId))

        val actual = KafkaListenerSpanNameConvention().getHighCardinalityKeyValues(context).toList()

        assertTrue(actual.contains(KeyValue.of(KafkaTracingAttributes.CONVERSATION_ID, correlationId)))
    }
}
