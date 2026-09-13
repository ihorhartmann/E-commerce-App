package com.hartmann.application.port.out.idempotency.dto;

import java.time.Instant;

public record HttpRequestRecord(
        String id,
        String requestHash,
        Integer httpCode,
        String response,
        Instant createdAt,
        Instant claimedAt,
        Instant completedAt
) {
}
