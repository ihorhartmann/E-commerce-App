package com.hartmann.frameworks.kafka

import com.hartmann.entities.exception.outermostOf
import com.hartmann.interfaceadapter.`in`.http.exception.HttpErrorCode
import com.hartmann.interfaceadapter.`in`.kafka.isPermanentFailure
import com.hartmann.interfaceadapter.common.metrics.TRANSPORT_KAFKA
import com.hartmann.interfaceadapter.common.metrics.recordFailureMetric
import com.hartmann.usecase.port.common.kafka.KafkaConst
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.listener.RecordInterceptor
import java.util.concurrent.TimeUnit

/**
 * Records every attempt at handling a message - each retry is one more - as `ecommerce.kafka.message.duration`, by its
 * topic, message type and outcome: handled, failed in a way a retry can fix, or rejected - failed for good, dead-lettered
 * without a retry. A failure is counted by its code too. The Go services record the same, under the same names.
 *
 * Spring Kafka calls it on the listener's thread, around every delivery of a record - the first and every redelivery
 * after the error handler's back-off.
 */
class KafkaMetricsInterceptor : RecordInterceptor<Any, Any> {
    private companion object {
        const val MESSAGE_DURATION = "ecommerce.kafka.message.duration"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_REJECTED = "rejected"
    }

    // the start of the attempt running on this listener thread
    private val attemptStart = ThreadLocal<Long>()

    override fun intercept(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>): ConsumerRecord<Any, Any> {
        attemptStart.set(System.nanoTime())
        return record
    }

    override fun success(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>) {
        recordAttemptMetric(record, OUTCOME_SUCCESS)
    }

    override fun failure(record: ConsumerRecord<Any, Any>, exception: Exception, consumer: Consumer<Any, Any>) {
        recordAttemptMetric(record, if (isPermanentFailure(exception)) OUTCOME_REJECTED else OUTCOME_FAILED)
        // a failure that isn't ours is reported as an internal error, as GlobalExceptionHandler does
        recordFailureMetric(outermostOf(exception)?.code?.code ?: HttpErrorCode.INTERNAL_ERROR.code, TRANSPORT_KAFKA)
    }

    override fun afterRecord(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>) {
        attemptStart.remove()
    }

    private fun recordAttemptMetric(record: ConsumerRecord<Any, Any>, outcome: String) {
        val start = attemptStart.get() ?: return
        Timer.builder(MESSAGE_DURATION)
            .tag("topic", record.topic())
            .tag("message.type", messageTypeOf(record))
            .tag("outcome", outcome)
            .register(Metrics.globalRegistry)
            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    }
}

private const val UNKNOWN_MESSAGE_TYPE = "unknown"

/** The record's messageType header, or `unknown` for a record without one. */
fun messageTypeOf(record: ConsumerRecord<*, *>): String =
    record.headers().lastHeader(KafkaConst.MESSAGE_TYPE)?.value()?.toString(Charsets.UTF_8) ?: UNKNOWN_MESSAGE_TYPE
