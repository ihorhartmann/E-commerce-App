package com.hartmann.application.port.out.kafka

import com.hartmann.application.saga.event.SagaEvent

interface OrderEventProducer {
    fun publish(event: SagaEvent)
}
