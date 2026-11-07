package com.hartmann.adapter.in.scheduled;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.application.port.out.outbox.OutboxMessageRepository;
import com.hartmann.application.port.out.outbox.dto.OutboxMessage;
import com.hartmann.application.port.out.tracing.TraceContextPropagator;
import io.micrometer.core.instrument.Metrics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Periodically publishes not-yet-sent outbox_messages rows to Kafka through the plain
 * KafkaTemplate, and marks the whole batch sent once every message in it publishes successfully.
 * Each message is sent as a child of the span it was written in - one batch can hold messages of
 * as many different traces.
 * <p>
 * Counts the published messages by type, and the failed runs.
 */
@Component
public class OutboxMessageRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxMessageRelay.class);
    private static final int BATCH_SIZE = 100;

    // the same names the other services use; how many messages wait, and for how long, is read from the database itself
    // (Alloy's postgres exporter), once for all replicas
    private static final String RELAY_FAILURES = "ecommerce.outbox.relay.failures";
    private static final String PUBLISHED_MESSAGES = "ecommerce.outbox.messages.published";
    private static final String STAGE_PUBLISH = "publish";

    private final OutboxMessageRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TraceContextPropagator traceContextPropagator;

    public OutboxMessageRelay(OutboxMessageRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                              TraceContextPropagator traceContextPropagator) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.traceContextPropagator = traceContextPropagator;
    }

    @Scheduled(fixedDelay = 200)
    public void relay() {
        var messages = repository.fetchUnsent(BATCH_SIZE);
        if (messages.isEmpty()) {
            return;
        }

        try {
            for (var message : messages) {
                traceContextPropagator.withParent(message.traceparent(), () -> kafkaTemplate.send(toRecord(message))).get();
            }
        } catch (Exception e) {
            LOG.atError().setMessage("outbox relay failed to publish message batch")
                    .setCause(e)
                    .addKeyValue("count", messages.size())
                    .log();
            Metrics.counter(RELAY_FAILURES, "stage", STAGE_PUBLISH).increment();
            return;
        }

        messages.forEach(message -> Metrics.counter(PUBLISHED_MESSAGES, "message.type", message.messageType()).increment());

        var messageIds = messages.stream().map(OutboxMessage::messageId).toList();
        repository.markSentBatch(messageIds, Instant.now());
    }

    private static ProducerRecord<String, String> toRecord(OutboxMessage message) {
        var record = new ProducerRecord<>(KafkaConst.PAYMENT_EVENTS_TOPIC, message.key(), message.payload());
        record.headers()
                .add(new RecordHeader(KafkaConst.MESSAGE_TYPE, message.messageType().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(KafkaConst.CORRELATION_ID, message.correlationId().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(KafkaConst.MESSAGE_ID, message.messageId().toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }
}
