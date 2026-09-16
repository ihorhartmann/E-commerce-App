package com.hartmann.usecase.port.out.outbox.dto

data class OutboxMessage(
    val messageId: String = "",
    val correlationId: String,
    val messageType: String,
    val topic: String,
    val key: String,
    val payload: String,
)
