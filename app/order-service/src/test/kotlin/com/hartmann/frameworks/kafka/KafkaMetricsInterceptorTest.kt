package com.hartmann.frameworks.kafka

import com.hartmann.interfaceadapter.`in`.http.exception.HttpErrorCode
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import com.hartmann.usecase.port.out.repository.exception.RepositoryErrorCode
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Optional

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KafkaMetricsInterceptorTest {
    private val registry = SimpleMeterRegistry()
    private val consumer = mockk<Consumer<Any, Any>>()
    private val interceptor = KafkaMetricsInterceptor()

    @BeforeEach
    fun addRegistry() {
        Metrics.addRegistry(registry)
    }

    @AfterEach
    fun removeRegistry() {
        Metrics.removeRegistry(registry)
    }

    private fun record(topic: String, messageType: String?): ConsumerRecord<Any, Any> {
        val headers = RecordHeaders().apply { messageType?.let { add(KafkaConst.MESSAGE_TYPE, it.toByteArray()) } }
        return ConsumerRecord(topic, 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", "value", headers, Optional.empty())
    }

    private fun attempts(topic: String, messageType: String, outcome: String): Long =
        registry.find("ecommerce.kafka.message.duration")
            .tags("topic", topic, "message.type", messageType, "outcome", outcome)
            .timer()?.count() ?: 0

    // the meters of the global registry outlive a test, and come into each new registry, with nothing recorded yet
    private fun recordedAttempts(): Long = registry.find("ecommerce.kafka.message.duration").timers().sumOf { it.count() }

    private fun failures(code: String): Double =
        registry.find("ecommerce.errors").tags("error.code", code, "transport", "kafka").counter()?.count() ?: 0.0

    @Test
    fun `success - records the attempt as a success, by its topic and message type`() {
        val record = record(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT)

        interceptor.intercept(record, consumer)
        interceptor.success(record, consumer)

        assertEquals(1, attempts(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, "success"))
        assertEquals(0.0, registry.find("ecommerce.errors").counters().sumOf { it.count() })
        verify { consumer wasNot Called }
    }

    @Test
    fun `success - records nothing for a record that wasn't intercepted`() {
        val record = record(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT)

        interceptor.success(record, consumer)

        assertEquals(0, recordedAttempts())
        verify { consumer wasNot Called }
    }

    @Test
    fun `failure - records a failure a retry can fix as failed, and counts it as an internal error`() {
        val record = record(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT)

        interceptor.intercept(record, consumer)
        interceptor.failure(record, RuntimeException("connection reset"), consumer)

        assertEquals(1, attempts(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, "failed"))
        assertEquals(1.0, failures(HttpErrorCode.INTERNAL_ERROR.code))
        verify { consumer wasNot Called }
    }

    @Test
    fun `failure - records a permanent failure as rejected, and counts it by its code`() {
        val record = record(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT)

        interceptor.intercept(record, consumer)
        interceptor.failure(record, OrderNotFoundException(7L), consumer)

        assertEquals(1, attempts(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, "rejected"))
        assertEquals(1.0, failures(RepositoryErrorCode.ORDER_NOT_FOUND.code))
        verify { consumer wasNot Called }
    }

    @Test
    fun `success - records a record without a messageType header as unknown`() {
        val record = record(KafkaConst.PAYMENT_EVENTS_TOPIC, null)

        interceptor.intercept(record, consumer)
        interceptor.success(record, consumer)

        assertEquals(1, attempts(KafkaConst.PAYMENT_EVENTS_TOPIC, "unknown", "success"))
        verify { consumer wasNot Called }
    }

    @Test
    fun `afterRecord - forgets the attempt's start, so nothing more is recorded for it`() {
        val record = record(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT)

        interceptor.intercept(record, consumer)
        interceptor.afterRecord(record, consumer)
        interceptor.success(record, consumer)

        assertEquals(0, recordedAttempts())
        verify { consumer wasNot Called }
    }
}
