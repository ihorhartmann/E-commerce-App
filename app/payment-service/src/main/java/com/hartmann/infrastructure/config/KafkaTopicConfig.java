package com.hartmann.infrastructure.config;

import com.hartmann.adapter.common.kafka.KafkaConst;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

@Configuration
public class KafkaTopicConfig {
    // A topic and its DLT get the same partition count so a dead-lettered record lands on the same
    // partition number it came from (see KafkaErrorHandlerConfig).
    private static final int PARTITIONS = 1;
    private static final int REPLICAS = 1;

    // Longer than the broker's default retention for the source topics, so a message that was
    // dead-lettered can still be redriven well after the fact.
    private static final Duration DLT_RETENTION = Duration.ofDays(30);

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(KafkaConst.PAYMENT_EVENTS_TOPIC)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentCommandsTopic() {
        return TopicBuilder.name(KafkaConst.PAYMENT_COMMANDS_TOPIC)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentCommandsDltTopic() {
        return TopicBuilder.name(KafkaConst.PAYMENT_COMMANDS_DLT_TOPIC)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(DLT_RETENTION.toMillis()))
                .build();
    }
}
