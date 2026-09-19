package com.hartmann.frameworks.config

import com.hartmann.usecase.port.common.kafka.KafkaConst
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import java.time.Duration

@Configuration
class KafkaTopicConfig {
    companion object {
        // A topic and its DLT get the same partition count so a dead-lettered record lands on the same
        // partition number it came from (see KafkaErrorHandlerConfig).
        private const val PARTITIONS = 1
        private const val REPLICAS = 1

        // Longer than the broker's default retention for the source topics, so a message that was
        // dead-lettered can still be redriven well after the fact.
        private val DLT_RETENTION: Duration = Duration.ofDays(30)
    }

    // Topics this service produces to (through the outbox relay)
    @Bean
    fun inventoryCommandsTopic(): NewTopic = topic(KafkaConst.INVENTORY_COMMANDS_TOPIC)

    @Bean
    fun shippingCommandsTopic(): NewTopic = topic(KafkaConst.SHIPPING_COMMANDS_TOPIC)

    @Bean
    fun paymentCommandsTopic(): NewTopic = topic(KafkaConst.PAYMENT_COMMANDS_TOPIC)

    // Topics this service consumes from, and their dead-letter topics
    @Bean
    fun paymentEventsTopic(): NewTopic = topic(KafkaConst.PAYMENT_EVENTS_TOPIC)

    @Bean
    fun shippingEventsTopic(): NewTopic = topic(KafkaConst.SHIPPING_EVENTS_TOPIC)

    @Bean
    fun paymentEventsDltTopic(): NewTopic = deadLetterTopic(KafkaConst.PAYMENT_EVENTS_DLT_TOPIC)

    @Bean
    fun shippingEventsDltTopic(): NewTopic = deadLetterTopic(KafkaConst.SHIPPING_EVENTS_DLT_TOPIC)

    private fun topic(name: String): NewTopic =
        TopicBuilder.name(name)
            .partitions(PARTITIONS)
            .replicas(REPLICAS)
            .build()

    private fun deadLetterTopic(name: String): NewTopic =
        TopicBuilder.name(name)
            .partitions(PARTITIONS)
            .replicas(REPLICAS)
            .config(TopicConfig.RETENTION_MS_CONFIG, DLT_RETENTION.toMillis().toString())
            .build()
}
