package com.hartmann.usecase.port.out.idempotency.dto

data class KafkaMessageMetadata(
    val messageId: String,
    val correlationId: String,
    val messageType: String,
)
