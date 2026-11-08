package com.hartmann.frameworks.kafka

import com.hartmann.interfaceadapter.`in`.kafka.failureKeyValues
import com.hartmann.usecase.port.common.kafka.KafkaConst
import io.micrometer.core.instrument.Metrics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import java.time.Duration

/**
 * Publishes a record that failed for good to its dead-letter topic, retrying the publish until it succeeds, and logs
 * it as dead-lettered once it is - the one line that tells a message given up on from one still being retried - and
 * counts it.
 * [failureKeyValues] describes the failure on that line: its code and attributes.
 */
class RetryableDeadLetterPublishingRecoverer(
    private val delegate: DeadLetterPublishingRecoverer,
    private val interval: Duration,
) : ConsumerRecordRecoverer {

    companion object {
        private val LOG = LoggerFactory.getLogger(RetryableDeadLetterPublishingRecoverer::class.java)

        // counted per message once it is published to its dead-letter topic - the same name the Go services use
        private const val DEAD_LETTERED = "ecommerce.kafka.message.dead_lettered"

        private const val TRACEPARENT = "traceparent"
        private const val TRACEPARENT_PARTS = 4

        // the MDC keys Micrometer Tracing puts a span's ids under
        private const val TRACE_ID = "traceId"
        private const val SPAN_ID = "spanId"
    }

    override fun accept(record: ConsumerRecord<*, *>, exception: Exception) {
        val mdcKeys = putInMdc(record)
        try {
            publish(record, exception)

            val event = LOG.atError().setMessage("kafka message dead-lettered")
                .addKeyValue("topic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
            failureKeyValues(exception).forEach { (key, value) -> event.addKeyValue(key, value) }
            event.log()
            Metrics.counter(DEAD_LETTERED, "topic", record.topic(), "message.type", messageTypeOf(record)).increment()
        } finally {
            mdcKeys.forEach(MDC::remove)
        }
    }

    private fun publish(record: ConsumerRecord<*, *>, exception: Exception) {
        while (true) {
            try {
                delegate.accept(record, exception)
                return
            } catch (e: RuntimeException) {
                if (Thread.currentThread().isInterrupted) {
                    throw e
                }

                LOG.atError().setMessage("failed to publish kafka message to the dead-letter topic, retrying")
                    .setCause(e)
                    .addKeyValue("key", record.key())
                    .log()
                try {
                    Thread.sleep(interval.toMillis())
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    // e already has a cause of its own - the publish failure - so the interruption is kept next to it
                    e.addSuppressed(interrupted)
                    throw e
                }
            }
        }
    }

    /**
     * The record's ids, and its trace when no span is current, as MDC entries for the lines logged here, the same
     * fields the consumer's lines have: the error handler runs after the consumer has taken its own entries out
     * again. Returns the keys it put, to be removed again.
     */
    private fun putInMdc(record: ConsumerRecord<*, *>): List<String> {
        val entries = buildMap {
            for (header in listOf(KafkaConst.MESSAGE_TYPE, KafkaConst.MESSAGE_ID, KafkaConst.CORRELATION_ID)) {
                headerValue(record, header)?.let { put(header, it) }
            }
            // traceparent: version-traceId-spanId-flags
            val traceparent = headerValue(record, TRACEPARENT)?.split('-')
            if (MDC.get(TRACE_ID) == null && traceparent?.size == TRACEPARENT_PARTS) {
                put(TRACE_ID, traceparent[1])
                put(SPAN_ID, traceparent[2])
            }
        }
        entries.forEach(MDC::put)
        return entries.keys.toList()
    }

    private fun headerValue(record: ConsumerRecord<*, *>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)
}
