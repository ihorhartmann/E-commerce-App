package com.hartmann.adapter.out.idempotency.row;

import java.time.Instant;

public record ProcessedKafkaMessageRow(
        String id,
        String correlationId,
        String messageType,
        Instant processedAt
) {
}
