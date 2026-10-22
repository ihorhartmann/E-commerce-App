package com.hartmann.frameworks.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import java.time.Duration

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
