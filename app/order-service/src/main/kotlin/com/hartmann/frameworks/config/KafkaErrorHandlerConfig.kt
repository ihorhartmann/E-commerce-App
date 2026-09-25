package com.hartmann.frameworks.config

import com.hartmann.interfaceadapter.`in`.kafka.exception.NonRetryableException
import com.hartmann.usecase.port.common.kafka.KafkaConst
import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.messaging.MessageHandlingException
import java.time.Duration

@Configuration
class KafkaErrorHandlerConfig {
    companion object {
        // 1 initial attempt + 4 retries = 5 attempts, waiting 1s, 2s, 4s, 8s (15s in total) between them before the record is dead-lettered.
        private const val MAX_RETRIES = 4
        private const val INITIAL_INTERVAL_MS = 1_000L
        private const val MULTIPLIER = 2.0

        private val DEAD_LETTER_RETRY_INTERVAL = Duration.ofSeconds(2)
    }

    @Bean
    fun kafkaErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): CommonErrorHandler {
        return createErrorHandler(kafkaTemplate).apply {
            // Anything not listed here is treated as transient and retried. Which failures repeat the
            // same way every time is decided by the consumer, which wraps them in NonRetryableException.
            addNotRetryableExceptions(
                NonRetryableException::class.java,
                // A required header is missing: @Header binding fails inside the framework, before the
                // consumer runs, so the consumer never gets the chance to classify it.
                MessageHandlingException::class.java,
            )
        }
    }

    // Same partition number as the source record (KafkaTopicConfig gives both topics the same
    // partition count). A publish failure is retried in place by
    // RetryableDeadLetterPublishingRecoverer below, so the record is never skipped.
    private fun createErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        val recoverer = RetryableDeadLetterPublishingRecoverer(
            DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
                TopicPartition(record.topic() + KafkaConst.DLT_SUFFIX, record.partition())
            },
            DEAD_LETTER_RETRY_INTERVAL,
        )

        val backOff = ExponentialBackOffWithMaxRetries(MAX_RETRIES).apply {
            setInitialInterval(INITIAL_INTERVAL_MS)
            setMultiplier(MULTIPLIER)
        }

        return DefaultErrorHandler(recoverer, backOff)
    }
}
