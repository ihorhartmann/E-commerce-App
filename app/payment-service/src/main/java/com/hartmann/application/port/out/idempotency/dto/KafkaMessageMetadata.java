package com.hartmann.application.port.out.idempotency.dto;

public record KafkaMessageMetadata(String messageId, String correlationId, String messageType) {
}
