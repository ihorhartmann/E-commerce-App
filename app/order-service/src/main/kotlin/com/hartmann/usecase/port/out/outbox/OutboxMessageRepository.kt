package com.hartmann.usecase.port.out.outbox

import com.hartmann.usecase.port.out.outbox.dto.OutboxMessage
import java.time.Instant

interface OutboxMessageRepository {
    /**
     * Writes message to the outbox. Must be called inside the same transaction as the business
     * mutation it accompanies, so the message is persisted if and only if that mutation commits.
     */
    fun insert(message: OutboxMessage)

    /** Returns up to limit not-yet-sent messages, oldest first. */
    fun fetchUnsent(limit: Int): List<OutboxMessage>

    /** Records that every message in messageIds was published to Kafka at sentAt. */
    fun markSentBatch(messageIds: List<String>, sentAt: Instant)
}
