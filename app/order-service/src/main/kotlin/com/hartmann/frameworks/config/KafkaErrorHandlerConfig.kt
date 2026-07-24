package com.hartmann.frameworks.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaErrorHandlerConfig {
    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler = DefaultErrorHandler(FixedBackOff(1_000L, 3))
}
