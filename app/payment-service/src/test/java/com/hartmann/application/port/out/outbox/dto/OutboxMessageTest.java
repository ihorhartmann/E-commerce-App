package com.hartmann.application.port.out.outbox.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OutboxMessageTest {

    @Test
    void createNew_createsAMessageWithoutAnIdSoReadingMessageIdFails() {
        var message = OutboxMessage.createNew("corr-1", "PaymentCompleted", "42", "{}");

        assertThrows(IllegalStateException.class, message::messageId);
    }

    @Test
    void createExisting_createsAMessageExposingTheGivenId() {
        var expectedMessageId = UUID.randomUUID();

        var message = OutboxMessage.createExisting(expectedMessageId, "corr-2", "PaymentCompleted", "43", "{}");

        assertEquals(expectedMessageId, message.messageId());
    }

    @Test
    void createExisting_rejectsANullId() {
        assertThrows(IllegalStateException.class, () -> OutboxMessage.createExisting(null, "corr-3", "PaymentCompleted", "44", "{}"));
    }
}
