package com.hartmann.interfaceadapter.out.idempotency

import com.hartmann.interfaceadapter.out.idempotency.row.HttpRequestRow
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface HttpRequestJpaRepository : JpaRepository<HttpRequestRow, String> {
    @Modifying
    @Query(
        value = """
            DELETE FROM orders.idempotent_http_requests WHERE ctid IN (
                SELECT ctid FROM orders.idempotent_http_requests WHERE created_at < :cutoff LIMIT :batchSize
            )
        """,
        nativeQuery = true,
    )
    fun deleteBatchOlderThan(cutoff: Instant, batchSize: Int): Int

    /**
     * ON CONFLICT DO NOTHING rather than catching a constraint-violation error: a repeated
     * idempotencyKey is a routine, expected outcome here, not an exceptional one, and this way
     * Postgres never raises an error (and never aborts the transaction) for it - the returned row
     * count alone (0 or 1) tells the caller which case it is.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO orders.idempotent_http_requests (id, request_hash, status, created_at, claimed_at)
            VALUES (:id, :requestHash, :status, :createdAt, :claimedAt)
            ON CONFLICT (id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(id: String, requestHash: String, status: String, createdAt: Instant, claimedAt: Instant): Int

    /**
     * Conditional UPDATE rather than a read-then-write: only advances claimedAt if the row is
     * still IN_PROGRESS and still older than cutoff, so two concurrent reclaim attempts can't both
     * succeed - the second one's WHERE clause simply matches zero rows.
     */
    @Modifying
    @Query(
        value = """
            UPDATE orders.idempotent_http_requests
            SET claimed_at = :claimedAt
            WHERE id = :id AND status = 'IN_PROGRESS' AND claimed_at < :cutoff
        """,
        nativeQuery = true,
    )
    fun reclaim(id: String, claimedAt: Instant, cutoff: Instant): Int

    /** Only an IN_PROGRESS row: a completed one holds a response that must stay replayable. */
    @Modifying
    @Query(
        value = "DELETE FROM orders.idempotent_http_requests WHERE id = :id AND status = 'IN_PROGRESS'",
        nativeQuery = true,
    )
    fun deleteInProgress(id: String): Int
}
