package com.hartmann.application.port.out.idempotency;

import com.hartmann.application.port.out.idempotency.dto.HttpRequestRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface HttpRequestStore {
    /** Returns true if a new IN_PROGRESS row was inserted, false if idempotencyKey already existed. */
    boolean insert(String idempotencyKey, String requestHash);

    Optional<HttpRequestRecord> find(String idempotencyKey);

    /** Reclaims a stale IN_PROGRESS row, resetting claimedAt. Returns false if it was already reclaimed by someone else. */
    boolean reclaim(String idempotencyKey, Duration ttl);

    /** Moves the row to COMPLETED with the response to replay. An empty response is stored as no body at all. */
    void complete(String idempotencyKey, int httpCode, String response);

    /** Deletes idempotencyKey's row if it is still IN_PROGRESS, so the key can be claimed again at once. */
    void release(String idempotencyKey);

    /** Deletes up to limit rows older than cutoff. Returns how many were deleted. */
    int deleteExpired(Instant cutoff, int limit);
}
