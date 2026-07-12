package com.hartmann.infrastructure.kafka

import com.hartmann.application.port.out.kafka.OrderEventProducer
import com.hartmann.application.saga.event.SagaEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Temporary Kafka adapter. Replace its body with a Kafka producer without changing application code. */
@Component
class StubOrderEventProducer : OrderEventProducer {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun publish(event: SagaEvent) = log.info("Kafka stub published {}", event)
}
