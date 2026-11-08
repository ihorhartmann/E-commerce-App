package com.hartmann.infrastructure.config;

import com.hartmann.infrastructure.kafka.KafkaMetricsInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.RecordInterceptor;

@Configuration
public class KafkaMetricsConfig {
    /** Spring Boot hands the only RecordInterceptor bean to the listener container factory. */
    @Bean
    public RecordInterceptor<Object, Object> kafkaMetricsInterceptor() {
        return new KafkaMetricsInterceptor();
    }
}
