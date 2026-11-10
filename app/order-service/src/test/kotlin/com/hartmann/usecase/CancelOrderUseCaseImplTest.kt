package com.hartmann.usecase

import com.hartmann.entities.model.CancellationReason
import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.common.tracing.CorrelationTagger
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CancelOrderUseCaseImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val orderKafkaProducer = mockk<OrderKafkaProducer>(relaxed = true)
    private val paymentClient = mockk<PaymentClient>(relaxed = true)
    private val correlationTagger = mockk<CorrelationTagger>(relaxed = true)
    private val transactionManager = mockk<PlatformTransactionManager>(relaxed = true)
    private val useCase =
        CancelOrderUseCaseImpl(orderRepository, orderKafkaProducer, paymentClient, correlationTagger, transactionManager)

    private fun orderOf(
        id: Long,
        customerId: UUID,
        paymentConfirmedAt: Instant? = null,
        cancellationRequestedAt: Instant? = null,
        cancelledAt: Instant? = null,
        version: Long = 0,
    ) = Order.createExisting(
        id = id,
        customerId = customerId,
        items = listOf(OrderItem.createExisting(1, 1, 1, "Keyboard", 1, Money(BigDecimal("10.00"), Currency.EUR))),
        address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = ""),
        paymentConfirmedAt = paymentConfirmedAt,
        cancellationReason = cancellationRequestedAt?.let { CancellationReason.CANCELLED_BY_CUSTOMER },
        cancellationRequestedAt = cancellationRequestedAt,
        cancelledAt = cancelledAt,
        version = version,
        createdAt = Instant.now(),
    )

    @Test
    fun `execute - requests the cancellation of a paid order, saves it and asks shipping to cancel the shipment`() {
        val orderId = 42L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val paymentConfirmedAt = Instant.now()
        val order = orderOf(orderId, customerId, paymentConfirmedAt)
        val storedOrder = orderOf(orderId, customerId, paymentConfirmedAt, cancellationRequestedAt = Instant.now(), version = 1)
        every { orderRepository.getOrder(orderId) } returns order
        every { orderRepository.save(order) } returns storedOrder

        val actual = useCase.execute(orderId, customerId)

        assertSame(storedOrder, actual)
        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        verify(exactly = 1) { orderRepository.getOrder(orderId) }
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 1) { orderKafkaProducer.publishCancelShipment(orderId.toString(), orderId) }
        verify(exactly = 0) { paymentClient.cancelPayment(any(), any()) }
        verify(exactly = 1) { correlationTagger.tag(orderId.toString()) }
    }

    @Test
    fun `execute - cancels an unpaid order's payment first, then reloads the order and marks it being cancelled`() {
        val orderId = 42L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = orderOf(orderId, customerId)
        val reloadedOrder = orderOf(orderId, customerId, version = 1)
        val storedOrder = orderOf(orderId, customerId, cancellationRequestedAt = Instant.now(), version = 2)
        every { orderRepository.getOrder(orderId) } returnsMany listOf(order, reloadedOrder)
        every { orderRepository.save(reloadedOrder) } returns storedOrder

        val actual = useCase.execute(orderId, customerId)

        assertSame(storedOrder, actual)
        assertEquals(OrderStatus.CANCEL_REQUESTED, reloadedOrder.status)
        verifyOrder {
            paymentClient.cancelPayment(orderId.toString(), orderId)
            orderRepository.getOrder(orderId)
            orderRepository.save(reloadedOrder)
        }
        verify(exactly = 1) { paymentClient.cancelPayment(orderId.toString(), orderId) }
        verify(exactly = 2) { orderRepository.getOrder(orderId) }
        verify(exactly = 1) { orderRepository.save(reloadedOrder) }

        verify(exactly = 0) { orderKafkaProducer.publishCancelShipment(any(), any()) }
        verify(exactly = 1) { correlationTagger.tag(orderId.toString()) }
    }

    @Test
    fun `execute - returns an unpaid order as it is when its PaymentCancelled has already cancelled it`() {
        val orderId = 42L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val cancelledAt = Instant.now()
        val order = orderOf(orderId, customerId)
        val reloadedOrder = orderOf(orderId, customerId, cancellationRequestedAt = cancelledAt, cancelledAt = cancelledAt, version = 1)
        every { orderRepository.getOrder(orderId) } returnsMany listOf(order, reloadedOrder)

        val actual = useCase.execute(orderId, customerId)

        assertSame(reloadedOrder, actual)
        assertEquals(OrderStatus.CANCELLED, reloadedOrder.status)
        verify(exactly = 1) { paymentClient.cancelPayment(orderId.toString(), orderId) }
        verify(exactly = 2) { orderRepository.getOrder(orderId) }
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderKafkaProducer.publishCancelShipment(any(), any()) }
    }

    @Test
    fun `execute - returns a paid order as it is when its cancellation is already under way`() {
        val orderId = 42L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = orderOf(orderId, customerId, paymentConfirmedAt = Instant.now(), cancellationRequestedAt = Instant.now())
        every { orderRepository.getOrder(orderId) } returns order

        val actual = useCase.execute(orderId, customerId)

        assertSame(order, actual)
        verify(exactly = 1) { orderRepository.getOrder(orderId) }
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderKafkaProducer.publishCancelShipment(any(), any()) }
        verify(exactly = 0) { paymentClient.cancelPayment(any(), any()) }
        verify(exactly = 1) { correlationTagger.tag(orderId.toString()) }
    }

    @Test
    fun `execute - reports another customer's order as not found, leaving it as it is`() {
        val orderId = 42L
        val order = orderOf(orderId, UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001"), paymentConfirmedAt = Instant.now())
        every { orderRepository.getOrder(orderId) } returns order

        assertThrows<OrderNotFoundException> {
            useCase.execute(orderId, UUID.fromString("0192f1d2-7c4b-7000-8000-000000000002"))
        }

        assertEquals(OrderStatus.PROCESSING, order.status)
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderKafkaProducer.publishCancelShipment(any(), any()) }
        verify(exactly = 0) { paymentClient.cancelPayment(any(), any()) }
        // tagged before the order is even read, so a rejected cancellation is found by its order id too
        verify(exactly = 1) { correlationTagger.tag(orderId.toString()) }
    }
}
