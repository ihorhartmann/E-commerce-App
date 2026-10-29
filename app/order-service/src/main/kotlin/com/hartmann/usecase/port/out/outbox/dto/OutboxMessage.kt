package com.hartmann.usecase.port.out.outbox.dto

import java.util.UUID

@ConsistentCopyVisibility
data class OutboxMessage private constructor(
    private val id: UUID?,
    val correlationId: String,
    val messageType: String,
    val topic: String,
    val key: String,
    val payload: String,
    val traceparent: String?,
) {
    /** Only a message read back from the outbox has an id - it is assigned by the database on insert. */
    val messageId: UUID
        get() = checkNotNull(id) { "outbox message has no id yet - it is assigned on insert" }

    companion object {
        /** A message about to be inserted: the database assigns its id. */
        fun createNew(correlationId: String, messageType: String, topic: String, key: String, payload: String, traceparent: String?) =
            OutboxMessage(null, correlationId, messageType, topic, key, payload, traceparent)

        /** A message already stored in the outbox. */
        fun createExisting(messageId: UUID, correlationId: String, messageType: String, topic: String, key: String, payload: String, traceparent: String?) =
            OutboxMessage(messageId, correlationId, messageType, topic, key, payload, traceparent)
    }
}
