package com.hartmann.infrastructure.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.KafkaFailures;
import io.micrometer.core.instrument.Metrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Publishes a record that failed for good to its dead-letter topic, retrying the publish until it succeeds, and logs
 * it as dead-lettered once it is - the one line that tells a message given up on from one still being retried - and
 * counts it.
 * KafkaFailures.failureKeyValues describes the failure on that line: its code and attributes.
 */
public class RetryableDeadLetterPublishingRecoverer implements ConsumerRecordRecoverer {

    private static final Logger LOG = LoggerFactory.getLogger(RetryableDeadLetterPublishingRecoverer.class);

    // counted per message once it is published to its dead-letter topic - the same name the other services use
    private static final String DEAD_LETTERED = "ecommerce.kafka.message.dead_lettered";

    private static final String TRACEPARENT = "traceparent";
    private static final int TRACEPARENT_PARTS = 4;

    // the MDC keys Micrometer Tracing puts a span's ids under
    private static final String TRACE_ID = "traceId";
    private static final String SPAN_ID = "spanId";

    private final DeadLetterPublishingRecoverer delegate;
    private final Duration interval;

    public RetryableDeadLetterPublishingRecoverer(DeadLetterPublishingRecoverer delegate, Duration interval) {
        this.delegate = delegate;
        this.interval = interval;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        var mdcKeys = putInMdc(record);
        try {
            publish(record, exception);

            var event = LOG.atError().setMessage("kafka message dead-lettered")
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset());
            KafkaFailures.failureKeyValues(exception).forEach(event::addKeyValue);
            event.log();
            var messageType = KafkaMetricsInterceptor.messageTypeOf(record);
            Metrics.counter(DEAD_LETTERED, "topic", record.topic(), "message.type", messageType).increment();
        } finally {
            mdcKeys.forEach(MDC::remove);
        }
    }

    private void publish(ConsumerRecord<?, ?> record, Exception exception) {
        while (true) {
            try {
                delegate.accept(record, exception);
                return;
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }

                LOG.atError().setMessage("failed to publish kafka message to the dead-letter topic, retrying")
                        .setCause(e)
                        .addKeyValue("key", record.key())
                        .log();
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    // e already has a cause of its own - the publish failure - so the interruption is kept next to it
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
    }

    // The record's ids, and its trace when no span is current, as MDC entries for the lines logged here, the same
    // fields the consumer's lines have: the error handler runs after the consumer has taken its own entries out again.
    // Returns the keys it put, to be removed again.
    private static List<String> putInMdc(ConsumerRecord<?, ?> record) {
        var entries = new LinkedHashMap<String, String>();
        for (var header : List.of(KafkaConst.MESSAGE_TYPE, KafkaConst.MESSAGE_ID, KafkaConst.CORRELATION_ID)) {
            var value = headerValue(record, header);
            if (value != null) {
                entries.put(header, value);
            }
        }
        // traceparent: version-traceId-spanId-flags
        var traceparent = headerValue(record, TRACEPARENT);
        var parts = traceparent == null ? new String[0] : traceparent.split("-");
        if (MDC.get(TRACE_ID) == null && parts.length == TRACEPARENT_PARTS) {
            entries.put(TRACE_ID, parts[1]);
            entries.put(SPAN_ID, parts[2]);
        }
        entries.forEach(MDC::put);
        return List.copyOf(entries.keySet());
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
