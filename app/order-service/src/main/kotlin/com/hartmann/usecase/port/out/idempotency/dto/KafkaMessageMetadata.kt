package com.hartmann.usecase.port.out.idempotency.dto

import java.util.UUID

data class KafkaMessageMetadata(
    val messageId: UUID,
    val correlationId: String,
    val messageType: String,
)
