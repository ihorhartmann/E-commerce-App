package com.hartmann.application.port.out.idempotency;

import com.hartmann.application.port.out.idempotency.dto.KafkaMessageMetadata;

import java.time.Instant;

/**
 * Records which Kafka messages have already been processed, so a redelivered message can be
 * recognized and skipped instead of re-running its business logic.
 */
public interface ProcessedKafkaMessageStore {
    /**
     * Records message as processed. Must be called inside the same transaction as the business
     * mutation it protects. Returns true if message.messageId() was already recorded before, in
     * which case the caller must not run the mutation again.
     */
    boolean markProcessed(KafkaMessageMetadata message);

    /** Deletes up to limit rows older than cutoff. Returns how many were deleted. */
    int deleteExpired(Instant cutoff, int limit);
}
