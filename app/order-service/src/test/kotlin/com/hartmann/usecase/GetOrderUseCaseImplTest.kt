package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GetOrderUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val useCase = GetOrderUseCaseImpl(orderRepository)

    private fun orderOf(id: Long, customerId: UUID) = Order.createExisting(
        id = id,
        customerId = customerId,
        items = listOf(OrderItem.createExisting(1, 1, 1, "Keyboard", 1, Money(BigDecimal("10.00"), Currency.EUR))),
        address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = ""),
        createdAt = Instant.now(),
    )

    @Test
    fun `execute - returns the order to the customer who placed it`() {
        val orderId = 42L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = orderOf(orderId, customerId)
        every { orderRepository.getOrder(orderId) } returns order

        val actual = useCase.execute(orderId, customerId)

        assertEquals(order, actual)
        verify(exactly = 1) { orderRepository.getOrder(orderId) }
    }

    @Test
    fun `execute - reports another customer's order as not found`() {
        val orderId = 42L
        every { orderRepository.getOrder(orderId) } returns
            orderOf(orderId, UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001"))

        assertThrows<OrderNotFoundException> {
            useCase.execute(orderId, UUID.fromString("0192f1d2-7c4b-7000-8000-000000000002"))
        }

        verify(exactly = 1) { orderRepository.getOrder(orderId) }
    }
}
