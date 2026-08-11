package com.hartmann.entities.model

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun testOrderItem(
    id: Long = 1,
    orderId: Long = 1,
    productId: Long = 1,
    quantity: Int = 1,
    price: Money = Money(BigDecimal("10.00"), Currency.USD),
) = OrderItem(id = id, orderId = orderId, productId = productId, quantity = quantity, price = price)

class OrderItemTest {

    @Test
    fun `creates an order item with all fields set`() {
        val item = testOrderItem(id = 1, orderId = 2, productId = 3, quantity = 4)

        assertEquals(1, item.id)
        assertEquals(2, item.orderId)
        assertEquals(3, item.productId)
        assertEquals(4, item.quantity)
    }

    @Test
    fun `rejects a negative id`() {
        assertFailsWith<IllegalArgumentException> { testOrderItem(id = -1) }
    }

    @Test
    fun `rejects a negative orderId`() {
        assertFailsWith<IllegalArgumentException> { testOrderItem(orderId = -1) }
    }

    @Test
    fun `rejects a zero productId`() {
        assertFailsWith<IllegalArgumentException> { testOrderItem(productId = 0) }
    }

    @Test
    fun `rejects a negative productId`() {
        assertFailsWith<IllegalArgumentException> { testOrderItem(productId = -1) }
    }

    @Test
    fun `rejects a zero quantity`() {
        assertFailsWith<IllegalArgumentException> { testOrderItem(quantity = 0) }
    }

    @Test
    fun `rejects a negative quantity`() {
        assertFailsWith<IllegalArgumentException> { testOrderItem(quantity = -1) }
    }
}
