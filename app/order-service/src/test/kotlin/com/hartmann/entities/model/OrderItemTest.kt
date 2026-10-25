package com.hartmann.entities.model

import com.hartmann.entities.model.exception.InconsistentOrderException
import com.hartmann.entities.model.exception.OrderInvariantViolatedException
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

private fun testPrice() = Money(BigDecimal("10.00"), Currency.EUR)

private fun newItem(productId: Long = 1, productName: String = "Keyboard", quantity: Int = 1) =
    OrderItem.createNew(productId = productId, productName = productName, quantity = quantity, price = testPrice())

private fun existingItem(id: Long = 1, orderId: Long = 1, quantity: Int = 1) = OrderItem.createExisting(
    id = id,
    orderId = orderId,
    productId = 1,
    productName = "Keyboard",
    quantity = quantity,
    price = testPrice(),
)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderItemTest {

    // --- createNew rules ---

    @Test
    fun `createNew - rejects a zero productId`() {
        assertThrows<OrderInvariantViolatedException> { newItem(productId = 0) }
    }

    @Test
    fun `createNew - rejects a negative productId`() {
        assertThrows<OrderInvariantViolatedException> { newItem(productId = -1) }
    }

    @Test
    fun `createNew - rejects a blank productName`() {
        assertThrows<OrderInvariantViolatedException> { newItem(productName = " ") }
    }

    @Test
    fun `createNew - rejects a zero quantity`() {
        assertThrows<OrderInvariantViolatedException> { newItem(quantity = 0) }
    }

    @Test
    fun `createNew - rejects a negative quantity`() {
        assertThrows<OrderInvariantViolatedException> { newItem(quantity = -1) }
    }

    // --- createNew ---

    @Test
    fun `createNew - creates an order item with id and orderId defaulted to zero`() {
        val productId = 3L
        val productName = "Mouse"
        val quantity = 4
        val price = Money(BigDecimal("2.50"), Currency.EUR)

        val item = OrderItem.createNew(productId = productId, productName = productName, quantity = quantity, price = price)

        assertEquals(0L, item.id)
        assertEquals(0L, item.orderId)
        assertEquals(productId, item.productId)
        assertEquals(productName, item.productName)
        assertEquals(quantity, item.quantity)
        assertEquals(price, item.price)
    }

    // --- createExisting ---

    @Test
    fun `createExisting - creates an order item with all fields set`() {
        val id = 1L
        val orderId = 2L
        val productId = 3L
        val productName = "Mouse"
        val quantity = 4
        val price = Money(BigDecimal("2.50"), Currency.EUR)

        val item = OrderItem.createExisting(
            id = id,
            orderId = orderId,
            productId = productId,
            productName = productName,
            quantity = quantity,
            price = price,
        )

        assertEquals(id, item.id)
        assertEquals(orderId, item.orderId)
        assertEquals(productId, item.productId)
        assertEquals(productName, item.productName)
        assertEquals(quantity, item.quantity)
        assertEquals(price, item.price)
    }

    @Test
    fun `createExisting - throws when id is not positive`() {
        assertThrows<InconsistentOrderException> { existingItem(id = 0) }
    }

    @Test
    fun `createExisting - throws when orderId is not positive`() {
        assertThrows<InconsistentOrderException> { existingItem(orderId = 0) }
    }

    @Test
    fun `createExisting - reports a stored item that breaks the item's own rules as an inconsistent order`() {
        assertThrows<InconsistentOrderException> { existingItem(quantity = 0) }
    }

    // --- equals ---

    @Test
    fun `equals - returns true for order items with the same id`() {
        val id = 1L
        val a = existingItem(id = id, quantity = 1)
        val b = existingItem(id = id, quantity = 5)

        assertEquals(a, b)
    }

    @Test
    fun `equals - returns false for order items with different ids`() {
        val a = existingItem(id = 1)
        val b = existingItem(id = 2)

        assertNotEquals(a, b)
    }

    @Test
    fun `equals - returns false when both order items are not yet persisted`() {
        val a = newItem()
        val b = newItem()

        assertNotEquals(a, b)
    }

    @Test
    fun `equals - returns false for an object of a different type`() {
        val item = existingItem()

        assertNotEquals(item, "not an order item")
    }
}
