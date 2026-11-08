package com.hartmann.infrastructure.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.application.port.out.repository.exception.PaymentNotFoundException;
import com.hartmann.application.port.out.repository.exception.RepositoryErrorCode;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
    void accept_countsThePublishedRecordAsDeadLetteredByItsTopicAndMessageType() {
        var headers = new RecordHeaders();
        headers.add(KafkaConst.MESSAGE_TYPE, KafkaConst.REFUND_REQUESTED_COMMAND.getBytes(StandardCharsets.UTF_8));
        var typedRecord = new ConsumerRecord<Object, Object>(KafkaConst.PAYMENT_COMMANDS_TOPIC, 0, 0L, 0L,
                TimestampType.CREATE_TIME, 0, 0, "key", "value", headers, Optional.empty());
        var recoverer = new RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1));
        var registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);

        try {
            recoverer.accept(typedRecord, exception);
        } finally {
            Metrics.removeRegistry(registry);
        }

        var deadLettered = registry.find("ecommerce.kafka.message.dead_lettered")
                .tags("topic", KafkaConst.PAYMENT_COMMANDS_TOPIC, "message.type", KafkaConst.REFUND_REQUESTED_COMMAND)
                .counter();
        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.count()).isEqualTo(1);
        verify(delegate, times(1)).accept(typedRecord, exception);
    }

    @Test
    void accept_logsThePublishedRecordAsDeadLetteredWithItsIdsItsTraceAndTheFailure() {
        var messageId = "0b8f6c1e-8f0a-4a8e-9d0a-7f1f2a3b4c5d";
        var correlationId = "corr-dlt";
        var traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        var spanId = "00f067aa0ba902b7";
        var headers = new RecordHeaders();
        headers.add(KafkaConst.MESSAGE_TYPE, KafkaConst.REFUND_REQUESTED_COMMAND.getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaConst.MESSAGE_ID, messageId.getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaConst.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        headers.add("traceparent", ("00-" + traceId + "-" + spanId + "-01").getBytes(StandardCharsets.UTF_8));
        var topic = KafkaConst.PAYMENT_COMMANDS_TOPIC;
        var partition = 2;
        var offset = 42L;
        var tracedRecord = new ConsumerRecord<Object, Object>(topic, partition, offset, 0L, TimestampType.CREATE_TIME, 0, 0,
                "key", "value", headers, Optional.empty());
        var orderId = 7L;
        var failure = PaymentNotFoundException.withOrderId(orderId);
        var recoverer = new RetryableDeadLetterPublishingRecoverer(delegate, Duration.ofMillis(1));
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(RetryableDeadLetterPublishingRecoverer.class);
        logger.addAppender(appender);

        try {
            recoverer.accept(tracedRecord, failure);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getMessage()).isEqualTo("kafka message dead-lettered");
        var actualKeyValues = new LinkedHashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> actualKeyValues.put(pair.key, pair.value));
        assertThat(actualKeyValues).isEqualTo(Map.of(
                "topic", topic,
                "partition", partition,
                "offset", offset,
                "exceptionMessage", failure.getMessage(),
                "code", RepositoryErrorCode.PAYMENT_NOT_FOUND.code(),
                "orderId", orderId));
        assertThat(event.getMDCPropertyMap()).isEqualTo(Map.of(
                KafkaConst.MESSAGE_TYPE, KafkaConst.REFUND_REQUESTED_COMMAND,
                KafkaConst.MESSAGE_ID, messageId,
                KafkaConst.CORRELATION_ID, correlationId,
                "traceId", traceId,
                "spanId", spanId));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        verify(delegate, times(1)).accept(tracedRecord, failure);
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
