package com.hartmann.adapter.out.outbox.row;

import com.hartmann.application.port.out.outbox.dto.OutboxMessage;

import java.time.Instant;

public record OutboxMessageRow(
        String id,
        String correlationId,
        String messageType,
        String key,
        String payload,
        Instant sentAt,
        Instant createdAt
) {
    public static OutboxMessageRow from(OutboxMessage message) {
        return new OutboxMessageRow(message.messageId(), message.correlationId(), message.messageType(), message.key(), message.payload(), null, null);
    }

    public static OutboxMessage toOutboxMessage(OutboxMessageRow row) {
        return new OutboxMessage(row.id(), row.correlationId(), row.messageType(), row.key(), row.payload());
    }
}
