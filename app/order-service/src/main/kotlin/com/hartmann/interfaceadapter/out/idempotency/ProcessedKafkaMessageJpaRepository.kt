package com.hartmann.interfaceadapter.out.idempotency

import com.hartmann.interfaceadapter.out.idempotency.row.ProcessedKafkaMessageRow
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ProcessedKafkaMessageJpaRepository : JpaRepository<ProcessedKafkaMessageRow, UUID> {
    @Modifying
    @Query(
        value = """
            DELETE FROM orders.processed_kafka_messages WHERE ctid IN (
                SELECT ctid FROM orders.processed_kafka_messages WHERE processed_at < :cutoff LIMIT :batchSize
            )
        """,
        nativeQuery = true,
    )
    fun deleteBatchOlderThan(cutoff: Instant, batchSize: Int): Int

    /**
     * ON CONFLICT DO NOTHING rather than plain save()/saveAndFlush(): a redelivered id is a
     * routine, expected outcome here, not an exceptional one, and this way Postgres never raises a
     * constraint-violation error (and never aborts the transaction) for it - the returned row
     * count alone (0 or 1) tells the caller which case it is.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO orders.processed_kafka_messages (id, correlation_id, message_type, processed_at)
            VALUES (:id, :correlationId, :messageType, :processedAt)
            ON CONFLICT (id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(id: UUID, correlationId: String, messageType: String, processedAt: Instant): Int
}
