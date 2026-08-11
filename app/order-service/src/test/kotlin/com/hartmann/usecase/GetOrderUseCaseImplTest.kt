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
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GetOrderUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val useCase = GetOrderUseCaseImpl(orderRepository)

    @Test
    fun `execute - returns the order fetched from the repository`() {
        val order = Order.create(
            userId = 1,
            items = listOf(OrderItem(1, 1, 1, 1, Money(BigDecimal("10.00"), Currency.USD))),
            address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = ""),
        ).copy(id = 42)
        every { orderRepository.getOrder(42) } returns order

        val result = useCase.execute(42)

        assertEquals(order, result)
        verify(exactly = 1) { orderRepository.getOrder(42) }
    }
}
