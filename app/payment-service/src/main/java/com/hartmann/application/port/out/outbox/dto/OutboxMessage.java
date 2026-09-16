package com.hartmann.application.port.out.outbox.dto;

public record OutboxMessage(
        String messageId,
        String correlationId,
        String messageType,
        String key,
        String payload
) {
}
