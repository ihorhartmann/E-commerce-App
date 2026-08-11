package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

private fun testItem() = OrderItem(
    id = 1,
    orderId = 1,
    productId = 1,
    quantity = 1,
    price = Money(BigDecimal("10.00"), Currency.USD),
)

private fun newOrder() = Order.create(userId = 1, items = listOf(testItem()), address = testAddress())

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PlaceOrderUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val inventoryClient = mockk<InventoryClient>()
    private val paymentClient = mockk<PaymentClient>()
    private val kafkaProducer = mockk<OrderKafkaProducer>(relaxed = true)

    private val useCase = PlaceOrderUseCaseImpl(orderRepository, inventoryClient, paymentClient, kafkaProducer)

    @Test
    fun `execute - places an order when reservation and payment both succeed`() {
        val order = newOrder()
        val createdOrder = order.copy(id = 1)
        every { orderRepository.save(order) } returns createdOrder
        every { inventoryClient.reserve(1, createdOrder.items) } just Runs
        every { paymentClient.submitPayment(1, createdOrder.totalPrice()) } returns
            SubmitPaymentResult(10L, "secret_123")

        val result = useCase.execute(order)

        assertEquals(createdOrder, result.order)
        assertEquals("secret_123", result.clientSecret)
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 0) { kafkaProducer.publishCancelReservation(any(), any()) }
    }

    @Test
    fun `execute - marks the order out of stock and rethrows when reservation reports insufficient stock`() {
        val order = newOrder()
        val createdOrder = order.copy(id = 1)
        val outOfStockOrder = createdOrder.outOfStock()
        every { orderRepository.save(order) } returns createdOrder
        every { orderRepository.save(outOfStockOrder) } returns outOfStockOrder
        every { inventoryClient.reserve(1, createdOrder.items) } throws InsufficientStockException("no stock")

        assertFailsWith<InsufficientStockException> { useCase.execute(order) }

        verify(exactly = 1) { orderRepository.save(outOfStockOrder) }
        verify(exactly = 0) { paymentClient.submitPayment(any(), any()) }
    }

    @Test
    fun `execute - fails the reservation and rethrows on any other reservation error`() {
        val order = newOrder()
        val createdOrder = order.copy(id = 1)
        val failedOrder = createdOrder.failReservation()
        every { orderRepository.save(order) } returns createdOrder
        every { orderRepository.save(failedOrder) } returns failedOrder
        every { inventoryClient.reserve(1, createdOrder.items) } throws Exception("inventory unreachable")

        assertFailsWith<Exception> { useCase.execute(order) }

        verify(exactly = 1) { orderRepository.save(failedOrder) }
        verify(exactly = 0) { paymentClient.submitPayment(any(), any()) }
    }

    @Test
    fun `execute - fails the payment, cancels the reservation and rethrows when payment submission errors`() {
        val order = newOrder()
        val createdOrder = order.copy(id = 1)
        val failedOrder = createdOrder.failPayment()
        every { orderRepository.save(order) } returns createdOrder
        every { orderRepository.save(failedOrder) } returns failedOrder
        every { inventoryClient.reserve(1, createdOrder.items) } just Runs
        every { paymentClient.submitPayment(1, createdOrder.totalPrice()) } throws Exception("gateway down")

        assertFailsWith<Exception> { useCase.execute(order) }

        verify(exactly = 1) { orderRepository.save(failedOrder) }
        verify(exactly = 1) { kafkaProducer.publishCancelReservation("1", 1L) }
    }
}
