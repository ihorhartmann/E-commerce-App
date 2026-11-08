package com.hartmann.frameworks.config

import com.hartmann.frameworks.kafka.KafkaMetricsInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.RecordInterceptor

@Configuration
class KafkaMetricsConfig {
    /** Spring Boot hands the only RecordInterceptor bean to the listener container factory. */
    @Bean
    fun kafkaMetricsInterceptor(): RecordInterceptor<Any, Any> = KafkaMetricsInterceptor()
}
