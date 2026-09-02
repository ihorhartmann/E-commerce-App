package com.hartmann.usecase.saga

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import com.hartmann.usecase.port.out.repository.OrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant

private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

private fun testItem() = OrderItem.createExisting(
    id = 1,
    orderId = 1,
    productId = 1,
    quantity = 2,
    price = Money(BigDecimal("10.00"), Currency.USD),
)

private fun placedOrder() =
    Order.createExisting(id = 1, userId = 1, items = listOf(testItem()), address = testAddress(), status = OrderStatus.PLACED, createdAt = Instant.now())

private fun processingOrder() = placedOrder().confirmPayment()

private fun orderWithShipment() = processingOrder().createShipment("TRACK-1")

private fun orderInDelivery() = orderWithShipment().startDelivery()

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class FulfillOrderSagaImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val orderKafkaProducer = mockk<OrderKafkaProducer>(relaxed = true)

    private val saga = FulfillOrderSagaImpl(orderRepository, orderKafkaProducer)

    private var savedOrder: Order? = null

    @BeforeEach
    fun setUp() {
        savedOrder = null
    }

    private fun stubGetOrder(order: Order) {
        every { orderRepository.getOrder(order.id) } returns order
    }

    private fun stubSave() {
        every { orderRepository.save(any()) } answers { firstArg<Order>().also { savedOrder = it } }
    }

    @Test
    fun `onPaymentCompleted - confirms payment and requests a shipment`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = placedOrder()
        stubGetOrder(order)
        stubSave()

        saga.onPaymentCompleted(orderId, correlationId)

        assertEquals(OrderStatus.PROCESSING, savedOrder?.status)
        verify(exactly = 1) { orderKafkaProducer.publishCreateShipment(correlationId, orderId, order.address) }
    }

    @Test
    fun `onPaymentFailed - fails the order and cancels the reservation`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = placedOrder()
        stubGetOrder(order)
        stubSave()

        saga.onPaymentFailed(orderId, correlationId)

        assertEquals(OrderStatus.CANCELLED, savedOrder?.status)
        verify(exactly = 1) { orderKafkaProducer.publishCancelReservation(correlationId, orderId) }
    }

    @Test
    fun `onPaymentExpired - expires the order and cancels the reservation`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = placedOrder()
        stubGetOrder(order)
        stubSave()

        saga.onPaymentExpired(orderId, correlationId)

        assertEquals(OrderStatus.CANCELLED, savedOrder?.status)
        verify(exactly = 1) { orderKafkaProducer.publishCancelReservation(correlationId, orderId) }
    }

    @Test
    fun `onShipmentCreated - attaches the tracking number and holds the reservation for the courier`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val trackingNumber = "TRACK-1"
        val order = processingOrder()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentCreated(orderId, trackingNumber, correlationId)

        assertEquals(trackingNumber, savedOrder?.shipment?.trackingNumber)
        verify(exactly = 1) { orderKafkaProducer.publishHoldReservation(correlationId, orderId) }
    }

    @Test
    fun `onShipmentInDelivery - marks the shipment picked up and fulfills the reservation`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = orderWithShipment()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentInDelivery(orderId, correlationId)

        assertEquals(true, savedOrder?.wasPickedUp())
        verify(exactly = 1) { orderKafkaProducer.publishFulfillReservation(correlationId, orderId) }
    }

    @Test
    fun `onShipmentDelivered - fulfills the order without publishing anything`() {
        val order = orderInDelivery()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentDelivered(1)

        assertEquals(OrderStatus.FULFILLED, savedOrder?.status)
        verify(exactly = 0) { orderKafkaProducer.publishCreateShipment(any(), any(), any()) }
        verify(exactly = 0) { orderKafkaProducer.publishHoldReservation(any(), any()) }
        verify(exactly = 0) { orderKafkaProducer.publishFulfillReservation(any(), any()) }
        verify(exactly = 0) { orderKafkaProducer.publishCancelReservation(any(), any()) }
        verify(exactly = 0) { orderKafkaProducer.publishRefundRequested(any(), any(), any(), any()) }
        verify(exactly = 0) { orderKafkaProducer.publishRestock(any(), any(), any()) }
    }

    @Test
    fun `onShipmentCancelled - after pickup only requests a refund - reservation already left the warehouse`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = orderInDelivery()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentCancelled(orderId, correlationId)

        assertEquals(OrderStatus.CANCEL_REQUESTED, savedOrder?.status)
        assertEquals(true, savedOrder?.awaitingReturn)
        verify(exactly = 0) { orderKafkaProducer.publishCancelReservation(any(), any()) }
        verify(exactly = 1) { orderKafkaProducer.publishRefundRequested(correlationId, orderId, any(), any()) }
    }

    @Test
    fun `onShipmentCancelled - before pickup also cancels the reservation`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = orderWithShipment()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentCancelled(orderId, correlationId)

        assertNotEquals(true, savedOrder?.awaitingReturn)
        verify(exactly = 1) { orderKafkaProducer.publishCancelReservation(correlationId, orderId) }
        verify(exactly = 1) { orderKafkaProducer.publishRefundRequested(correlationId, orderId, any(), any()) }
    }

    @Test
    fun `onShipmentLost - requests a refund without touching the reservation`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = orderInDelivery()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentLost(orderId, correlationId)

        assertEquals(OrderStatus.CANCEL_REQUESTED, savedOrder?.status)
        assertNotEquals(true, savedOrder?.awaitingReturn)
        verify(exactly = 0) { orderKafkaProducer.publishCancelReservation(any(), any()) }
        verify(exactly = 1) { orderKafkaProducer.publishRefundRequested(correlationId, orderId, any(), any()) }
    }

    @Test
    fun `onShipmentReturnedToInventory - publishes a restock command with the order's items`() {
        val orderId = 1L
        val correlationId = "corr-1"
        val order = orderInDelivery().cancelShipment()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentReturnedToInventory(orderId, correlationId)

        verify(exactly = 1) {
            orderKafkaProducer.publishRestock(correlationId, orderId, listOf(RestockItemDto(testItem().productId, testItem().quantity)))
        }
    }

    @Test
    fun `onRefundCompleted - confirms the refund with the reported amount and currency`() {
        val amount = BigDecimal("20.00")
        val order = orderInDelivery().cancelShipment().requestRefund()
        stubGetOrder(order)
        stubSave()

        saga.onRefundCompleted(1, Money(amount, Currency.USD), "corr-1")

        assertEquals(amount, savedOrder?.refund?.amount?.amount)
        assertEquals(true, savedOrder?.refund?.isConfirmed)
    }

    @Test
    fun `onRefundFailed - flags the refund as failed`() {
        val order = orderInDelivery().cancelShipment().requestRefund()
        stubGetOrder(order)
        stubSave()

        saga.onRefundFailed(1, "corr-1")

        assertNotNull(savedOrder?.refund?.failedAt)
    }
}
