package com.hartmann.interfaceadapter.`in`.scheduled

import com.hartmann.usecase.port.out.outbox.OutboxMessageRepository
import com.hartmann.usecase.port.out.outbox.dto.OutboxMessage
import com.hartmann.usecase.port.common.tracing.TraceContextPropagator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OutboxMessageRelayTest {
    private val repository = mockk<OutboxMessageRepository>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
    private val traceContextPropagator = mockk<TraceContextPropagator> {
        every { withParent(any(), any<() -> Any?>()) } answers { secondArg<() -> Any?>().invoke() }
    }
    private val relay = OutboxMessageRelay(repository, kafkaTemplate, traceContextPropagator)

    private fun message(traceparent: String?) =
        OutboxMessage.createExisting(UUID.randomUUID(), "corr-1", "CancelReservation", "inventory.commands", "42", "{}", traceparent)

    @Test
    fun `relay - publishes every message as a child of its own span and marks the batch sent`() {
        val firstTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val first = message(firstTraceparent)
        val second = message(null)
        every { repository.fetchUnsent(any()) } returns listOf(first, second)
        every { kafkaTemplate.send(any<ProducerRecord<String, String>>()) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, String>>())

        relay.relay()

        verifyOrder {
            traceContextPropagator.withParent(firstTraceparent, any<() -> Any?>())
            traceContextPropagator.withParent(null, any<() -> Any?>())
        }
        verify(exactly = 2) { traceContextPropagator.withParent(any(), any<() -> Any?>()) }
        verify(exactly = 2) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
        verify(exactly = 1) { repository.markSentBatch(listOf(first.messageId, second.messageId), any()) }
    }

    @Test
    fun `relay - does not mark the batch sent when a message fails to publish`() {
        every { repository.fetchUnsent(any()) } returns listOf(message(null))
        every { kafkaTemplate.send(any<ProducerRecord<String, String>>()) } returns
            CompletableFuture.failedFuture(IllegalStateException("broker down"))

        relay.relay()

        verify(exactly = 1) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
        verify(exactly = 0) { repository.markSentBatch(any(), any()) }
    }

    @Test
    fun `relay - does nothing when there is no unsent message`() {
        every { repository.fetchUnsent(any()) } returns emptyList()

        relay.relay()

        verify(exactly = 0) { traceContextPropagator.withParent(any(), any<() -> Any?>()) }
        verify(exactly = 0) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
        verify(exactly = 0) { repository.markSentBatch(any(), any()) }
    }
}
