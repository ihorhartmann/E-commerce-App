package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.common.tracing.CorrelationTagger
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CancelOrderUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val orderKafkaProducer = mockk<OrderKafkaProducer>(relaxed = true)
    private val correlationTagger = mockk<CorrelationTagger>(relaxed = true)
    private val useCase = CancelOrderUseCaseImpl(orderRepository, orderKafkaProducer, correlationTagger)

    private fun orderOf(id: Long, customerId: UUID, status: OrderStatus, version: Long = 0) = Order.createExisting(
        id = id,
        customerId = customerId,
        items = listOf(OrderItem.createExisting(1, 1, 1, "Keyboard", 1, Money(BigDecimal("10.00"), Currency.EUR))),
        address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = ""),
        status = status,
        version = version,
        createdAt = Instant.now(),
    )

    @Test
    fun `execute - requests the cancellation, saves the order and asks shipping to cancel the shipment`() {
        val orderId = 42L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = orderOf(orderId, customerId, OrderStatus.PROCESSING)
        val storedOrder = orderOf(orderId, customerId, OrderStatus.CANCEL_REQUESTED, version = 1)
        every { orderRepository.getOrder(orderId) } returns order
        every { orderRepository.save(order) } returns storedOrder

        val actual = useCase.execute(orderId, customerId)

        assertSame(storedOrder, actual)
        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 1) { orderKafkaProducer.publishCancelShipment(orderId.toString(), orderId) }
        verify(exactly = 1) { correlationTagger.tag(orderId.toString()) }
    }

    @Test
    fun `execute - returns the order as it is when its cancellation is already under way`() {
        val orderId = 42L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = orderOf(orderId, customerId, OrderStatus.CANCEL_REQUESTED)
        every { orderRepository.getOrder(orderId) } returns order

        val actual = useCase.execute(orderId, customerId)

        assertSame(order, actual)
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderKafkaProducer.publishCancelShipment(any(), any()) }
        verify(exactly = 1) { correlationTagger.tag(orderId.toString()) }
    }

    @Test
    fun `execute - reports another customer's order as not found, leaving it as it is`() {
        val orderId = 42L
        val order = orderOf(orderId, UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001"), OrderStatus.PROCESSING)
        every { orderRepository.getOrder(orderId) } returns order

        assertThrows<OrderNotFoundException> {
            useCase.execute(orderId, UUID.fromString("0192f1d2-7c4b-7000-8000-000000000002"))
        }

        assertEquals(OrderStatus.PROCESSING, order.status)
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderKafkaProducer.publishCancelShipment(any(), any()) }
        // tagged before the order is even read, so a rejected cancellation is found by its order id too
        verify(exactly = 1) { correlationTagger.tag(orderId.toString()) }
    }
}
