package com.hartmann.interfaceadapter.`in`.scheduled

import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.out.outbox.OutboxMessageRepository
import com.hartmann.usecase.port.out.outbox.dto.OutboxMessage
import com.hartmann.usecase.port.common.tracing.TraceContextPropagator
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Periodically publishes not-yet-sent outbox_messages rows to Kafka through the plain
 * KafkaTemplate, and marks the whole batch sent once every message in it publishes successfully.
 * Each message is sent as a child of the span it was written in - one batch can hold messages of
 * as many different traces.
 */
@Component
class OutboxMessageRelay(
    private val repository: OutboxMessageRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val traceContextPropagator: TraceContextPropagator,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(OutboxMessageRelay::class.java)
        private const val BATCH_SIZE = 100
    }

    @Scheduled(fixedDelay = 200)
    fun relay() {
        val messages = repository.fetchUnsent(BATCH_SIZE)
        if (messages.isEmpty()) {
            return
        }

        try {
            messages.forEach { message ->
                traceContextPropagator.withParent(message.traceparent) { kafkaTemplate.send(toRecord(message)).get() }
            }
        } catch (e: Exception) {
            LOG.error("outbox relay failed to publish message batch, count={}", messages.size, e)
            return
        }

        val messageIds = messages.map { it.messageId }
        repository.markSentBatch(messageIds, Instant.now())
    }

    private fun toRecord(message: OutboxMessage): ProducerRecord<String, String> {
        val record = ProducerRecord(message.topic, message.key, message.payload)
        record.headers()
            .add(RecordHeader(KafkaConst.MESSAGE_TYPE, message.messageType.toByteArray(StandardCharsets.UTF_8)))
            .add(RecordHeader(KafkaConst.CORRELATION_ID, message.correlationId.toByteArray(StandardCharsets.UTF_8)))
            .add(RecordHeader(KafkaConst.MESSAGE_ID, message.messageId.toString().toByteArray(StandardCharsets.UTF_8)))
        return record
    }
}
