package com.hartmann.infrastructure.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RetryableDeadLetterPublishingRecovererTest {

    @Mock
    private DeadLetterPublishingRecoverer delegate;

    private final ConsumerRecord<Object, Object> record = new ConsumerRecord<>("topic", 0, 0L, "key", "value");
    private final Exception exception = new RuntimeException("handler failure");

    @Test
    void accept_publishesWithoutRetryingWhenTheDelegateSucceeds() {
        var recoverer = new RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1));

        recoverer.accept(record, exception);

        verify(delegate, times(1)).accept(record, exception);
    }

    @Test
    void accept_retriesAFailingPublishUntilItSucceeds() {
        int publishFailures = 2;
        var callCount = new AtomicInteger(0);
        doAnswer(_ -> {
            if (callCount.incrementAndGet() <= publishFailures) {
                throw new RuntimeException("dead-letter topic unavailable");
            }
            return null;
        }).when(delegate).accept(record, exception);
        var recoverer = new RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1));

        recoverer.accept(record, exception);

        verify(delegate, times(publishFailures + 1)).accept(record, exception);
    }

    @Test
    void accept_stopsRetryingAndRethrowsThePublishFailureWhenTheThreadIsInterrupted() {
        var publishException = new RuntimeException("dead-letter topic unavailable");
        doThrow(publishException).when(delegate).accept(record, exception);
        var recoverer = new RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofSeconds(30));

        Thread.currentThread().interrupt();
        try {
            var thrown = assertThrows(RuntimeException.class, () -> recoverer.accept(record, exception));
            assertSame(publishException, thrown);
        } finally {
            // Clear the interrupted flag so it doesn't bleed into later tests on this thread.
            Thread.interrupted();
        }
        verify(delegate, times(1)).accept(record, exception);
    }
}
