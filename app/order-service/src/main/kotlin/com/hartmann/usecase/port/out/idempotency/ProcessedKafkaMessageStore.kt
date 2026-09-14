package com.hartmann.usecase.port.out.idempotency

import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import java.time.Instant

interface ProcessedKafkaMessageStore {
    /**
     * Records message as processed. Must be called inside the same transaction as the business
     * mutation it protects. Returns true if message.messageId was already recorded before, in
     * which case the caller must not run the mutation again.
     */
    fun markProcessed(message: KafkaMessageMetadata): Boolean

    /** Deletes up to limit rows older than cutoff. Returns how many were deleted. */
    fun deleteExpired(cutoff: Instant, limit: Int): Int
}
