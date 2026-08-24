package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.out.repository.OrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.hartmann.entities.model.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDateTime

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GetOrderUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val useCase = GetOrderUseCaseImpl(orderRepository)

    @Test
    fun `execute - returns the order fetched from the repository`() {
        val orderId = 42L
        val order = Order.createExisting(
            id = orderId,
            userId = 1,
            items = listOf(OrderItem.createExisting(1, 1, 1, 1, Money(BigDecimal("10.00"), Currency.USD))),
            address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = ""),
            status = OrderStatus.PLACED,
            createdAt = LocalDateTime.now(),
        )
        every { orderRepository.getOrder(orderId) } returns order

        val result = useCase.execute(orderId)

        assertEquals(order, result)
        verify(exactly = 1) { orderRepository.getOrder(orderId) }
    }
}
