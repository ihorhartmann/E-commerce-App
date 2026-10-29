package com.hartmann.application.port.out.outbox.dto;

import java.util.Objects;
import java.util.UUID;

public final class OutboxMessage {
    private final UUID messageId;
    private final String correlationId;
    private final String messageType;
    private final String key;
    private final String payload;
    private final String traceparent;

    private OutboxMessage(UUID messageId, String correlationId, String messageType, String key, String payload, String traceparent) {
        this.messageId = messageId;
        this.correlationId = correlationId;
        this.messageType = messageType;
        this.key = key;
        this.payload = payload;
        this.traceparent = traceparent;
    }

    // A message about to be inserted: the database assigns its id.
    public static OutboxMessage createNew(String correlationId, String messageType, String key, String payload, String traceparent) {
        return new OutboxMessage(null, correlationId, messageType, key, payload, traceparent);
    }

    // A message already stored in the outbox.
    public static OutboxMessage createExisting(UUID messageId, String correlationId, String messageType, String key, String payload,
                                               String traceparent) {
        if (messageId == null) {
            throw new IllegalStateException("an existing outbox message must have an id");
        }
        return new OutboxMessage(messageId, correlationId, messageType, key, payload, traceparent);
    }

    // Only a message read back from the outbox has an id - it is assigned by the database on insert.
    public UUID messageId() {
        if (messageId == null) {
            throw new IllegalStateException("outbox message has no id yet - it is assigned on insert");
        }
        return messageId;
    }

    public String correlationId() {
        return correlationId;
    }

    public String messageType() {
        return messageType;
    }

    public String key() {
        return key;
    }

    public String payload() {
        return payload;
    }

    public String traceparent() {
        return traceparent;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OutboxMessage other
                && Objects.equals(messageId, other.messageId)
                && Objects.equals(correlationId, other.correlationId)
                && Objects.equals(messageType, other.messageType)
                && Objects.equals(key, other.key)
                && Objects.equals(payload, other.payload)
                && Objects.equals(traceparent, other.traceparent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, correlationId, messageType, key, payload, traceparent);
    }

    @Override
    public String toString() {
        return "OutboxMessage{messageId=" + messageId + ", correlationId=" + correlationId + ", messageType=" + messageType
                + ", key=" + key + ", traceparent=" + traceparent + "}";
    }
}
