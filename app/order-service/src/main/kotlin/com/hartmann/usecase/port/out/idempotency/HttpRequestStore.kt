package com.hartmann.usecase.port.out.idempotency

import com.hartmann.usecase.port.out.idempotency.dto.HttpRequestRecord
import java.time.Duration
import java.time.Instant

/**
 * Persistence primitives for the idempotent_http_requests table. Holds no decision logic of its
 * own - HttpIdempotencyService (interfaceadapter.in.http.idempotency) owns deciding what a
 * conflict or a stale record means; this store never branches on it.
 */
interface HttpRequestStore {
    /** Returns true if a new IN_PROGRESS row was inserted, false if idempotencyKey already existed. */
    fun insert(idempotencyKey: String, requestHash: String): Boolean

    fun find(idempotencyKey: String): HttpRequestRecord?

    /** Reclaims a stale IN_PROGRESS row, resetting claimedAt. Returns false if it was already reclaimed by someone else. */
    fun reclaim(idempotencyKey: String, ttl: Duration): Boolean

    /** Moves the row to COMPLETED with the response to replay. An empty [response] is stored as no body at all. */
    fun complete(idempotencyKey: String, httpCode: Int, response: String)

    /** Deletes idempotencyKey's row if it is still IN_PROGRESS, so the key can be claimed again at once. */
    fun release(idempotencyKey: String)

    /** Deletes up to limit rows older than cutoff. Returns how many were deleted. */
    fun deleteExpired(cutoff: Instant, limit: Int): Int
}
