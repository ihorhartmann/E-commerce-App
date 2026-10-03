package com.hartmann.usecase

import com.hartmann.entities.model.CancellationReason
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
import com.hartmann.entities.model.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

private fun testItem() = OrderItem.createExisting(
    id = 1,
    orderId = 1,
    productId = 1,
    quantity = 1,
    price = Money(BigDecimal("10.00"), Currency.USD),
)

private fun testOrder(id: Long = 0): Order =
    if (id == 0L) {
        Order.createNew(userId = 1, items = listOf(testItem()), address = testAddress())
    } else {
        Order.createExisting(id = id, userId = 1, items = listOf(testItem()), address = testAddress(), status = OrderStatus.PLACED, createdAt = Instant.now())
    }

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PlaceOrderUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val inventoryClient = mockk<InventoryClient>()
    private val paymentClient = mockk<PaymentClient>()
    private val kafkaProducer = mockk<OrderKafkaProducer>(relaxed = true)

    private val useCase = PlaceOrderUseCaseImpl(orderRepository, inventoryClient, paymentClient, kafkaProducer)

    @Test
    fun `execute - places an order when reservation and payment both succeed`() {
        val orderId = 1L
        val newOrder = testOrder()
        val order = testOrder(id = orderId)
        every { orderRepository.save(newOrder) } returns order
        every { inventoryClient.reserve(orderId, order.items) } just Runs
        every { paymentClient.submitPayment(orderId, order.totalPrice()) } returns
            SubmitPaymentResult(10L, "secret_123")

        val result = useCase.execute(newOrder)

        assertEquals(order, result.order)
        assertEquals("secret_123", result.clientSecret)
        verify(exactly = 1) { orderRepository.save(newOrder) }
        verify(exactly = 0) { kafkaProducer.publishCancelReservation(any(), any()) }
    }

    @Test
    fun `execute - marks the order out of stock and rethrows when reservation reports insufficient stock`() {
        val orderId = 1L
        val newOrder = testOrder()
        val order = testOrder(id = orderId)
        every { orderRepository.save(newOrder) } returns order
        every { orderRepository.save(order) } returns order
        every { inventoryClient.reserve(orderId, order.items) } throws InsufficientStockException("no stock")

        assertThrows<InsufficientStockException> { useCase.execute(newOrder) }

        assertEquals(CancellationReason.OUT_OF_STOCK, order.cancellationReason)
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 0) { paymentClient.submitPayment(any(), any()) }
    }

    @Test
    fun `execute - fails the reservation and rethrows on any other reservation error`() {
        val orderId = 1L
        val newOrder = testOrder()
        val order = testOrder(id = orderId)
        every { orderRepository.save(newOrder) } returns order
        every { orderRepository.save(order) } returns order
        every { inventoryClient.reserve(orderId, order.items) } throws Exception("inventory unreachable")

        assertThrows<Exception> { useCase.execute(newOrder) }

        assertEquals(CancellationReason.RESERVATION_FAILED, order.cancellationReason)
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 0) { paymentClient.submitPayment(any(), any()) }
    }

    @Test
    fun `execute - fails the payment, cancels the reservation and rethrows when payment submission errors`() {
        val orderId = 1L
        val newOrder = testOrder()
        val order = testOrder(id = orderId)
        every { orderRepository.save(newOrder) } returns order
        every { orderRepository.save(order) } returns order
        every { inventoryClient.reserve(orderId, order.items) } just Runs
        every { paymentClient.submitPayment(orderId, order.totalPrice()) } throws Exception("gateway down")

        assertThrows<Exception> { useCase.execute(newOrder) }

        assertEquals(CancellationReason.PAYMENT_FAILED, order.cancellationReason)
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 1) { kafkaProducer.publishCancelReservation(orderId.toString(), orderId) }
    }
}
