package com.hartmann.interfaceadapter.out.outbox

import com.hartmann.interfaceadapter.out.outbox.row.OutboxMessageRow
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface OutboxMessageJpaRepository : JpaRepository<OutboxMessageRow, String> {
    @Query(
        value = "SELECT * FROM orders.outbox_messages WHERE sent_at IS NULL ORDER BY created_at LIMIT :limit",
        nativeQuery = true,
    )
    fun findUnsent(limit: Int): List<OutboxMessageRow>

    @Modifying
    @Query(
        value = "UPDATE orders.outbox_messages SET sent_at = :sentAt WHERE id IN (:messageIds)",
        nativeQuery = true,
    )
    fun markSentBatch(messageIds: List<String>, sentAt: Instant): Int
}
