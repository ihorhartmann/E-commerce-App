package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.`in`.usecase.FindCustomerOrdersUseCase.Companion.MAX_PAGE_SIZE
import com.hartmann.usecase.port.`in`.usecase.exception.InvalidPageException
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.dto.OrderPage
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
class FindCustomerOrdersUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val useCase = FindCustomerOrdersUseCaseImpl(orderRepository)

    private fun orderOf(id: Long, customerId: UUID) = Order.createExisting(
        id = id,
        customerId = customerId,
        items = listOf(OrderItem.createExisting(1, 1, 1, "Keyboard", 1, Money(BigDecimal("10.00"), Currency.EUR))),
        address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = ""),
        createdAt = Instant.now(),
    )

    @Test
    fun `execute - returns the requested page of the customer's orders`() {
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val page = 1
        val size = 2
        val expected = OrderPage(listOf(orderOf(4, customerId)), totalElements = 3)
        every { orderRepository.findCustomerOrders(customerId, page, size) } returns expected

        val actual = useCase.execute(customerId, page, size)

        assertEquals(expected, actual)
        verify(exactly = 1) { orderRepository.findCustomerOrders(customerId, page, size) }
    }

    @Test
    fun `execute - rejects a negative page without reading the orders`() {
        assertThrows<InvalidPageException> {
            useCase.execute(UUID.randomUUID(), -1, 20)
        }

        verify(exactly = 0) { orderRepository.findCustomerOrders(any(), any(), any()) }
    }

    @Test
    fun `execute - rejects a zero size without reading the orders`() {
        assertThrows<InvalidPageException> {
            useCase.execute(UUID.randomUUID(), 0, 0)
        }

        verify(exactly = 0) { orderRepository.findCustomerOrders(any(), any(), any()) }
    }

    @Test
    fun `execute - rejects a size above the maximum without reading the orders`() {
        assertThrows<InvalidPageException> {
            useCase.execute(UUID.randomUUID(), 0, MAX_PAGE_SIZE + 1)
        }

        verify(exactly = 0) { orderRepository.findCustomerOrders(any(), any(), any()) }
    }
}
