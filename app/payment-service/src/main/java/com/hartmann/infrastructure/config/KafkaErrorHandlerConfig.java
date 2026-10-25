package com.hartmann.infrastructure.config;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.KafkaFailures;
import com.hartmann.infrastructure.kafka.RetryableDeadLetterPublishingRecoverer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.util.backoff.FixedBackOff;

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

        // A permanent failure - a malformed payload, a business rule rejecting the message, a missing
        // payment - can't succeed on a later attempt, so it gets no retries and is dead-lettered at once.
        // Any other failure gets the default back-off.
        errorHandler.setBackOffFunction((_, exception) ->
                KafkaFailures.isPermanentFailure(exception) ? new FixedBackOff(0, 0) : null);
        // A required header is missing: @Header binding fails inside the framework, before the
        // consumer runs, so the failure is no PaymentServiceException.
        errorHandler.addNotRetryableExceptions(MessageHandlingException.class);
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
