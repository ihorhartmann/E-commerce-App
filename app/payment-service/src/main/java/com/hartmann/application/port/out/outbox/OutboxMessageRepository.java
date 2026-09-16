package com.hartmann.application.port.out.outbox;

import com.hartmann.application.port.out.outbox.dto.OutboxMessage;

import java.time.Instant;
import java.util.List;

public interface OutboxMessageRepository {
    /**
     * Writes message to the outbox. Must be called inside the same transaction as the business
     * mutation it accompanies, so the message is persisted if and only if that mutation commits.
     */
    void insert(OutboxMessage message);

    /** Returns up to limit not-yet-sent messages, oldest first. */
    List<OutboxMessage> fetchUnsent(int limit);

    /** Records that every message in messageIds was published to Kafka at sentAt. */
    void markSentBatch(List<String> messageIds, Instant sentAt);
}
