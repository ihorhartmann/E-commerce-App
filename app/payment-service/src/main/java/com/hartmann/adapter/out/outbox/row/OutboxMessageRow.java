package com.hartmann.adapter.out.outbox.row;

import com.hartmann.application.port.out.outbox.dto.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessageRow(
        UUID id,
        String correlationId,
        String messageType,
        String key,
        String payload,
        String traceparent,
        Instant sentAt,
        Instant createdAt
) {
    public static OutboxMessageRow from(OutboxMessage message) {
        return new OutboxMessageRow(null, message.correlationId(), message.messageType(), message.key(), message.payload(),
                message.traceparent(), null, null);
    }

    public static OutboxMessage toOutboxMessage(OutboxMessageRow row) {
        return OutboxMessage.createExisting(row.id(), row.correlationId(), row.messageType(), row.key(), row.payload(), row.traceparent());
    }
}
