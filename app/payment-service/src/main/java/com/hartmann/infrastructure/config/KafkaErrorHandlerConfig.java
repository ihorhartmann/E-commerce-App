package com.hartmann.infrastructure.config;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.exception.NonRetryableException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.messaging.MessageHandlingException;

import java.time.Duration;

@Configuration
public class KafkaErrorHandlerConfig {
    // 1 initial attempt + 4 retries = 5 attempts, waiting 1s, 2s, 4s, 8s (15s in total) between them before the record is dead-lettered.
    private static final int MAX_RETRIES = 4;
    private static final long INITIAL_INTERVAL_MS = 1_000L;
    private static final double MULTIPLIER = 2.0;

    private static final Duration DEAD_LETTER_RETRY_INTERVAL = Duration.ofSeconds(2);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var errorHandler = createErrorHandler(kafkaTemplate);

        // Anything not listed here is treated as transient and retried. Which failures repeat the same
        // way every time is decided by the consumer, which wraps them in NonRetryableException.
        errorHandler.addNotRetryableExceptions(
                NonRetryableException.class,
                // A required header is missing: @Header binding fails inside the framework, before the
                // consumer runs, so the consumer never gets the chance to classify it.
                MessageHandlingException.class
        );
        return errorHandler;
    }

    // Same partition number as the source record (KafkaTopicConfig gives both topics the same
    // partition count). A publish failure is retried in place by
    // RetryableDeadLetterPublishingRecoverer below, so the record is never skipped.
    private DefaultErrorHandler createErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new RetryableDeadLetterPublishingRecoverer(
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, _) -> new TopicPartition(record.topic() + KafkaConst.DLT_SUFFIX, record.partition())),
                DEAD_LETTER_RETRY_INTERVAL);

        var backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
