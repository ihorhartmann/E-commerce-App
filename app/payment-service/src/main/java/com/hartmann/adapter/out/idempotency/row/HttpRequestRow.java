package com.hartmann.adapter.out.idempotency.row;

import java.time.Instant;

public record HttpRequestRow(
        String id,
        String requestHash,
        String status,
        Integer httpCode,
        String response,
        Instant createdAt,
        Instant claimedAt,
        Instant completedAt
) {
}
