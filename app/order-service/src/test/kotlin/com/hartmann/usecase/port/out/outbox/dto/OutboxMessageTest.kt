package com.hartmann.usecase.port.out.outbox.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OutboxMessageTest {

    @Test
    fun `createNew - creates a message without an id, so reading messageId fails`() {
        val message = OutboxMessage.createNew("corr-1", "CancelReservation", "inventory.commands", "42", "{}", null)

        assertThrows<IllegalStateException> { message.messageId }
    }

    @Test
    fun `createExisting - creates a message exposing the given id`() {
        val expectedMessageId = UUID.randomUUID()

        val message = OutboxMessage.createExisting(expectedMessageId, "corr-2", "CancelReservation", "inventory.commands", "43", "{}", null)

        assertEquals(expectedMessageId, message.messageId)
    }
}
