package com.hartmann.interfaceadapter.out.repository.entity

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderEntityTest {

    @Test
    fun `markUpdated - stamps the time of the change`() {
        val entity = OrderEntity()
        val before = Instant.now()

        entity.markUpdated()

        val actual = entity.updatedAt
        assertNotNull(actual)
        assertTrue(!actual!!.isBefore(before), "updatedAt $actual is before the change at $before")
    }
}
