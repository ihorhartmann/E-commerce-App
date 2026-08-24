package com.hartmann.entities.model

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

private fun testPrice() = Money(BigDecimal("10.00"), Currency.USD)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderItemTest {

    // --- constructor ---

    @Test
    fun `constructor - rejects a zero productId`() {
        assertThrows<IllegalArgumentException> { OrderItem.createNew(productId = 0, quantity = 1, price = testPrice()) }
    }

    @Test
    fun `constructor - rejects a negative productId`() {
        assertThrows<IllegalArgumentException> { OrderItem.createNew(productId = -1, quantity = 1, price = testPrice()) }
    }

    @Test
    fun `constructor - rejects a zero quantity`() {
        assertThrows<IllegalArgumentException> { OrderItem.createNew(productId = 1, quantity = 0, price = testPrice()) }
    }

    @Test
    fun `constructor - rejects a negative quantity`() {
        assertThrows<IllegalArgumentException> { OrderItem.createNew(productId = 1, quantity = -1, price = testPrice()) }
    }

    // --- createNew ---

    @Test
    fun `createNew - creates an order item with id and orderId defaulted to zero`() {
        val productId = 3L
        val quantity = 4
        val item = OrderItem.createNew(productId = productId, quantity = quantity, price = testPrice())

        assertEquals(0L, item.id)
        assertEquals(0L, item.orderId)
        assertEquals(productId, item.productId)
        assertEquals(quantity, item.quantity)
    }

    // --- createExisting ---

    @Test
    fun `createExisting - creates an order item with all fields set`() {
        val id = 1L
        val orderId = 2L
        val productId = 3L
        val quantity = 4
        val item = OrderItem.createExisting(id = id, orderId = orderId, productId = productId, quantity = quantity, price = testPrice())

        assertEquals(id, item.id)
        assertEquals(orderId, item.orderId)
        assertEquals(productId, item.productId)
        assertEquals(quantity, item.quantity)
    }

    @Test
    fun `createExisting - throws when id is not positive`() {
        assertThrows<IllegalArgumentException> {
            OrderItem.createExisting(id = 0, orderId = 1, productId = 1, quantity = 1, price = testPrice())
        }
    }

    @Test
    fun `createExisting - throws when orderId is not positive`() {
        assertThrows<IllegalArgumentException> {
            OrderItem.createExisting(id = 1, orderId = 0, productId = 1, quantity = 1, price = testPrice())
        }
    }

    // --- equals ---

    @Test
    fun `equals - returns true for order items with the same id`() {
        val id = 1L
        val a = OrderItem.createExisting(id = id, orderId = 1, productId = 1, quantity = 1, price = testPrice())
        val b = OrderItem.createExisting(id = id, orderId = 1, productId = 1, quantity = 5, price = testPrice())

        assertEquals(a, b)
    }

    @Test
    fun `equals - returns false for order items with different ids`() {
        val a = OrderItem.createExisting(id = 1, orderId = 1, productId = 1, quantity = 1, price = testPrice())
        val b = OrderItem.createExisting(id = 2, orderId = 1, productId = 1, quantity = 1, price = testPrice())

        assertNotEquals(a, b)
    }

    @Test
    fun `equals - returns false when both order items are not yet persisted`() {
        val a = OrderItem.createNew(productId = 1, quantity = 1, price = testPrice())
        val b = OrderItem.createNew(productId = 1, quantity = 1, price = testPrice())

        assertNotEquals(a, b)
    }

    @Test
    fun `equals - returns false for an object of a different type`() {
        val item = OrderItem.createExisting(id = 1, orderId = 1, productId = 1, quantity = 1, price = testPrice())

        assertNotEquals(item, "not an order item")
    }
}
