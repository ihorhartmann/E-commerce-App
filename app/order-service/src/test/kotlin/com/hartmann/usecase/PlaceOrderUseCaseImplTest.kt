package com.hartmann.usecase

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.exception.ErrorCode
import com.hartmann.entities.model.CancellationReason
import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.ReservedItem
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import com.hartmann.usecase.port.common.tracing.CorrelationTagger
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

private fun testRequestedItems(productId: Long) = listOf(RequestedItem(productId = productId, quantity = 2))

private fun testReservedItems(productId: Long) =
    listOf(ReservedItem(productId, "Keyboard", 2, Money(BigDecimal("10.00"), Currency.EUR)))

/** The order as the repository returns it once stored. */
private fun storedOrder(id: Long, customerId: UUID, productId: Long): Order = Order.createExisting(
    id = id,
    customerId = customerId,
    items = listOf(OrderItem.createExisting(1, id, productId, "Keyboard", 2, Money(BigDecimal("10.00"), Currency.EUR))),
    address = testAddress(),
    status = OrderStatus.PLACED,
    createdAt = Instant.now(),
)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PlaceOrderUseCaseImplTest {
    private enum class TestErrorCode(override val title: String) : ErrorCode {
        TEST_RESERVATION_REJECTED("Test reservation rejected"),
    }

    // A failure the inventory port reports - e.g. insufficient stock, whose own exception lives in the adapter
    // layer this test must not depend on. The use case passes any such failure on as it is.
    private class TestReservationRejectedException :
        ConflictException(TestErrorCode.TEST_RESERVATION_REJECTED, "Reservation rejected")

    private val orderRepository = mockk<OrderRepository>()
    private val inventoryClient = mockk<InventoryClient>()
    private val paymentClient = mockk<PaymentClient>()
    private val kafkaProducer = mockk<OrderKafkaProducer>(relaxed = true)
    private val correlationTagger = mockk<CorrelationTagger>(relaxed = true)
    private val transactionManager = mockk<PlatformTransactionManager>(relaxed = true)

    private val useCase =
        PlaceOrderUseCaseImpl(orderRepository, inventoryClient, paymentClient, kafkaProducer, correlationTagger, transactionManager)

    @Test
    fun `execute - reserves the stock under a fresh id, stores the order of the reserved items and submits its payment`() {
        val orderId = 7L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val productId = 3L
        val requestedItems = testRequestedItems(productId)
        val clientSecret = "secret_123"
        val order = storedOrder(orderId, customerId, productId)
        val newOrder = slot<Order>()
        val reservedItems = testReservedItems(productId)
        every { orderRepository.nextId() } returns orderId
        every { inventoryClient.reserve(orderId, requestedItems) } returns reservedItems
        every { orderRepository.create(capture(newOrder)) } returns order
        every { paymentClient.submitPayment(orderId, order.totalPrice()) } returns SubmitPaymentResult(10L, clientSecret)

        val result = useCase.execute(customerId, requestedItems, testAddress())

        assertSame(order, result.order)
        assertEquals(clientSecret, result.clientSecret)
        assertEquals(orderId, newOrder.captured.id)
        assertEquals(customerId, newOrder.captured.customerId)
        assertEquals(reservedItems.single().name, newOrder.captured.items.single().productName)
        assertEquals(Money(BigDecimal("20.00"), Currency.EUR), newOrder.captured.totalPrice())
        verify(exactly = 1) { orderRepository.nextId() }
        verify(exactly = 1) { inventoryClient.reserve(orderId, requestedItems) }
        verify(exactly = 1) { orderRepository.create(any()) }
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 1) { paymentClient.submitPayment(orderId, order.totalPrice()) }
        verify(exactly = 0) { kafkaProducer.publishCancelReservation(any(), any()) }
        // nothing to compensate, so no transaction of its own is opened around the remote calls
        verify(exactly = 0) { transactionManager.getTransaction(any()) }
        verify(exactly = 1) { correlationTagger.tagCurrentSpan(orderId.toString()) }
    }

    @Test
    fun `execute - stores no order and rethrows when the stock can't be reserved`() {
        val orderId = 7L
        val requestedItems = testRequestedItems(productId = 3L)
        every { orderRepository.nextId() } returns orderId
        val expected = TestReservationRejectedException()
        every { inventoryClient.reserve(orderId, requestedItems) } throws expected

        val actual = assertThrows<TestReservationRejectedException> {
            useCase.execute(UUID.randomUUID(), requestedItems, testAddress())
        }

        assertSame(expected, actual)

        verify(exactly = 1) { inventoryClient.reserve(orderId, requestedItems) }
        verify(exactly = 0) { orderRepository.create(any()) }
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { paymentClient.submitPayment(any(), any()) }
        verify(exactly = 0) { kafkaProducer.publishCancelReservation(any(), any()) }
        // tagged before the reservation, so a checkout that fails right there is found by its order id too
        verifyOrder {
            correlationTagger.tagCurrentSpan(orderId.toString())
            inventoryClient.reserve(orderId, requestedItems)
        }
        verify(exactly = 1) { correlationTagger.tagCurrentSpan(orderId.toString()) }
    }

    @Test
    fun `execute - releases the reservation and rethrows when the reserved order can't be stored`() {
        val orderId = 7L
        val productId = 3L
        val requestedItems = testRequestedItems(productId)
        val expected = RuntimeException("db down")
        every { orderRepository.nextId() } returns orderId
        every { inventoryClient.reserve(orderId, requestedItems) } returns testReservedItems(productId)
        every { orderRepository.create(any()) } throws expected

        val actual = assertThrows<RuntimeException> { useCase.execute(UUID.randomUUID(), requestedItems, testAddress()) }

        assertSame(expected, actual)
        verify(exactly = 1) { orderRepository.create(any()) }
        verify(exactly = 1) { kafkaProducer.publishCancelReservation(orderId.toString(), orderId) }
        verify(exactly = 0) { paymentClient.submitPayment(any(), any()) }
        verify(exactly = 1) { correlationTagger.tagCurrentSpan(orderId.toString()) }
    }

    @Test
    fun `execute - fails the payment, cancels the reservation in the same transaction and rethrows when payment submission errors`() {
        val orderId = 7L
        val productId = 3L
        val requestedItems = testRequestedItems(productId)
        val order = storedOrder(orderId, UUID.randomUUID(), productId)
        every { orderRepository.nextId() } returns orderId
        every { inventoryClient.reserve(orderId, requestedItems) } returns testReservedItems(productId)
        every { orderRepository.create(any()) } returns order
        every { orderRepository.save(order) } returns order
        every { paymentClient.submitPayment(orderId, order.totalPrice()) } throws Exception("gateway down")

        assertThrows<Exception> { useCase.execute(UUID.randomUUID(), requestedItems, testAddress()) }

        assertEquals(CancellationReason.PAYMENT_FAILED, order.cancellationReason)
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 1) { kafkaProducer.publishCancelReservation(orderId.toString(), orderId) }
        // the cancelled order and the command that releases its stock are committed together
        verifyOrder {
            transactionManager.getTransaction(any())
            orderRepository.save(order)
            kafkaProducer.publishCancelReservation(orderId.toString(), orderId)
            transactionManager.commit(any())
        }
        verify(exactly = 1) { transactionManager.commit(any()) }
        verify(exactly = 1) { correlationTagger.tagCurrentSpan(orderId.toString()) }
    }
}
