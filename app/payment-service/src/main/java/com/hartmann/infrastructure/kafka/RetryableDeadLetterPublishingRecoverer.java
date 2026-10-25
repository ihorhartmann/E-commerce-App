package com.hartmann.infrastructure.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.time.Duration;

public class RetryableDeadLetterPublishingRecoverer implements ConsumerRecordRecoverer {

    private static final Logger LOG = LoggerFactory.getLogger(RetryableDeadLetterPublishingRecoverer.class);

    private final DeadLetterPublishingRecoverer delegate;
    private final Duration interval;

    public RetryableDeadLetterPublishingRecoverer(DeadLetterPublishingRecoverer delegate, Duration interval) {
        this.delegate = delegate;
        this.interval = interval;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        while (true) {
            try {
                delegate.accept(record, exception);
                return;
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }

                LOG.error("failed to publish record to the dead-letter topic, retrying", e);
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
}
