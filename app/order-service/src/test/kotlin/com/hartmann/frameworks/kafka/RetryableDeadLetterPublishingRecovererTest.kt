package com.hartmann.frameworks.kafka

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import com.hartmann.usecase.port.out.repository.exception.RepositoryErrorCode
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import java.time.Duration
import java.util.Optional

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RetryableDeadLetterPublishingRecovererTest {

    private val delegate = mockk<DeadLetterPublishingRecoverer>()
    private val record = ConsumerRecord("topic", 0, 0L, "key", "value")
    private val exception = RuntimeException("handler failure")

    @Test
    fun `accept - publishes without retrying when the delegate succeeds`() {
        every { delegate.accept(record, exception) } returns Unit
        val recoverer = RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1))

        recoverer.accept(record, exception)

        verify(exactly = 1) { delegate.accept(record, exception) }
    }

    @Test
    fun `accept - counts the published record as dead-lettered, by its topic and message type`() {
        val headers = RecordHeaders().apply { add(KafkaConst.MESSAGE_TYPE, KafkaConst.PAYMENT_COMPLETED_EVENT.toByteArray()) }
        val topic = "payment.events"
        val typedRecord = ConsumerRecord(topic, 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", "value", headers, Optional.empty())
        every { delegate.accept(typedRecord, exception) } returns Unit
        val recoverer = RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1))
        val registry = SimpleMeterRegistry()
        Metrics.addRegistry(registry)

        try {
            recoverer.accept(typedRecord, exception)
        } finally {
            Metrics.removeRegistry(registry)
        }

        val deadLettered = registry.find("ecommerce.kafka.message.dead_lettered")
            .tags("topic", topic, "message.type", KafkaConst.PAYMENT_COMPLETED_EVENT)
            .counter()
        assertEquals(1.0, deadLettered?.count())
        verify(exactly = 1) { delegate.accept(typedRecord, exception) }
    }

    @Test
    fun `accept - logs the published record as dead-lettered with its ids, its trace and the failure`() {
        val messageId = "0b8f6c1e-8f0a-4a8e-9d0a-7f1f2a3b4c5d"
        val correlationId = "corr-dlt"
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val spanId = "00f067aa0ba902b7"
        val headers = RecordHeaders().apply {
            add(KafkaConst.MESSAGE_TYPE, KafkaConst.PAYMENT_COMPLETED_EVENT.toByteArray())
            add(KafkaConst.MESSAGE_ID, messageId.toByteArray())
            add(KafkaConst.CORRELATION_ID, correlationId.toByteArray())
            add("traceparent", "00-$traceId-$spanId-01".toByteArray())
        }
        val topic = "payment.events"
        val partition = 2
        val offset = 42L
        val tracedRecord = ConsumerRecord(topic, partition, offset, 0L, TimestampType.CREATE_TIME, 0, 0, "key", "value", headers, Optional.empty())
        val orderId = 7L
        val failure = OrderNotFoundException(orderId)
        every { delegate.accept(tracedRecord, failure) } returns Unit
        val recoverer = RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1))
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(RetryableDeadLetterPublishingRecoverer::class.java) as Logger
        logger.addAppender(appender)

        try {
            recoverer.accept(tracedRecord, failure)
        } finally {
            logger.detachAppender(appender)
        }

        val event = appender.list.single()
        assertEquals(Level.ERROR, event.level)
        assertEquals("kafka message dead-lettered", event.message)
        val expectedKeyValues = mapOf<String, Any?>(
            "topic" to topic,
            "partition" to partition,
            "offset" to offset,
            "exceptionMessage" to failure.message,
            "code" to RepositoryErrorCode.ORDER_NOT_FOUND.code,
            "orderId" to orderId,
        )
        assertEquals(expectedKeyValues, event.keyValuePairs.associate { it.key to it.value })
        val expectedMdc = mapOf(
            KafkaConst.MESSAGE_TYPE to KafkaConst.PAYMENT_COMPLETED_EVENT,
            KafkaConst.MESSAGE_ID to messageId,
            KafkaConst.CORRELATION_ID to correlationId,
            "traceId" to traceId,
            "spanId" to spanId,
        )
        assertEquals(expectedMdc, event.mdcPropertyMap)
        assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty())
        verify(exactly = 1) { delegate.accept(tracedRecord, failure) }
    }

    @Test
    fun `accept - retries a failing publish until it succeeds`() {
        val publishFailures = 2
        var calls = 0
        every { delegate.accept(record, exception) } answers {
            calls++
            if (calls <= publishFailures) {
                throw RuntimeException("dead-letter topic unavailable")
            }
        }
        val recoverer = RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1))

        recoverer.accept(record, exception)

        verify(exactly = publishFailures + 1) { delegate.accept(record, exception) }
    }

    @Test
    fun `accept - stops retrying and rethrows the publish failure when the thread is interrupted`() {
        val publishException = RuntimeException("dead-letter topic unavailable")
        every { delegate.accept(record, exception) } throws publishException
        val recoverer = RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofSeconds(30))

        Thread.currentThread().interrupt()
        try {
            val thrown = assertThrows<RuntimeException> {
                recoverer.accept(record, exception)
            }
            assertSame(thrown, publishException)
        } finally {
            // Clear the interrupted flag so it doesn't bleed into later tests on this thread.
            Thread.interrupted()
        }
        verify(exactly = 1) { delegate.accept(record, exception) }
    }
}
