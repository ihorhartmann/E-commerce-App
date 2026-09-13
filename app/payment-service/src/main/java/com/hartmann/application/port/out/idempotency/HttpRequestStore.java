package com.hartmann.application.port.out.idempotency;

import com.hartmann.application.port.out.idempotency.dto.HttpRequestRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface HttpRequestStore {
    /** Returns true if a new IN_PROGRESS row was inserted, false if idempotencyKey already existed. */
    boolean insert(String idempotencyKey, String requestHash);

    Optional<HttpRequestRecord> get(String idempotencyKey);

    /** Reclaims a stale IN_PROGRESS row, resetting claimedAt. Returns false if it was already reclaimed by someone else. */
    boolean reclaim(String idempotencyKey, Duration ttl);

    void complete(String idempotencyKey, int httpCode, String response);

    /** Deletes up to limit rows older than cutoff. Returns how many were deleted. */
    int deleteExpired(Instant cutoff, int limit);
}
