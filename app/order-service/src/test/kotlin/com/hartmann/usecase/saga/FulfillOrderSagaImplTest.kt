package com.hartmann.usecase.saga

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.`in`.saga.event.PaymentCompletedEvent
import com.hartmann.usecase.port.`in`.saga.event.PaymentFailedEvent
import com.hartmann.usecase.port.`in`.saga.event.RefundCompletedEvent
import com.hartmann.usecase.port.`in`.saga.event.RefundFailedEvent
import com.hartmann.usecase.port.`in`.saga.event.ShipmentCreationFailedEvent
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import com.hartmann.usecase.port.out.repository.OrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

private fun testItem() = OrderItem(
    id = 1,
    orderId = 1,
    productId = 1,
    quantity = 2,
    price = Money(BigDecimal("10.00"), Currency.USD),
)

private fun placedOrder() =
    Order.create(userId = 1, items = listOf(testItem()), address = testAddress()).copy(id = 1)

private fun processingOrder() = placedOrder().confirmPayment()

private fun orderWithShipment() = processingOrder().createShipment("TRACK-1")

private fun orderInDelivery() = orderWithShipment().startDelivery()

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class FulfillOrderSagaImplTest {

    private val orderRepository = mockk<OrderRepository>()
    private val orderKafkaProducer = mockk<OrderKafkaProducer>(relaxed = true)

    private val saga = FulfillOrderSagaImpl(orderRepository, orderKafkaProducer)

    private var savedOrder: Order? = null

    @BeforeTest
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
        val order = placedOrder()
        stubGetOrder(order)
        stubSave()

        saga.onPaymentCompleted(PaymentCompletedEvent(1), "corr-1")

        assertEquals(OrderStatus.PROCESSING, savedOrder?.status)
        verify(exactly = 1) { orderKafkaProducer.publishCreateShipment("corr-1", 1, order.address) }
    }

    @Test
    fun `onPaymentFailed - fails the order and cancels the reservation`() {
        val order = placedOrder()
        stubGetOrder(order)
        stubSave()

        saga.onPaymentFailed(PaymentFailedEvent(1), "corr-1")

        assertEquals(OrderStatus.CANCELLED, savedOrder?.status)
        verify(exactly = 1) { orderKafkaProducer.publishCancelReservation("corr-1", 1) }
    }

    @Test
    fun `onShipmentCreationFailed - cancels the reservation and requests a refund`() {
        val order = processingOrder()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentCreationFailed(ShipmentCreationFailedEvent(1), "corr-1")

        assertEquals(OrderStatus.CANCEL_REQUESTED, savedOrder?.status)
        assertNotNull(savedOrder?.refund)
        verify(exactly = 1) { orderKafkaProducer.publishCancelReservation("corr-1", 1) }
        verify(exactly = 1) { orderKafkaProducer.publishRefundRequested("corr-1", 1, any(), any()) }
    }

    @Test
    fun `onShipmentCreated - attaches the tracking number and holds the reservation for the courier`() {
        val order = processingOrder()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentCreated(1, "TRACK-1", "corr-1")

        assertEquals("TRACK-1", savedOrder?.shipment?.trackingNumber)
        verify(exactly = 1) { orderKafkaProducer.publishHoldReservation("corr-1", 1) }
    }

    @Test
    fun `onShipmentInDelivery - marks the shipment picked up and fulfills the reservation`() {
        val order = orderWithShipment()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentInDelivery(1, "corr-1")

        assertEquals(true, savedOrder?.wasPickedUp())
        verify(exactly = 1) { orderKafkaProducer.publishFulfillReservation("corr-1", 1) }
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
        val order = orderInDelivery()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentCancelled(1, "corr-1")

        assertEquals(OrderStatus.CANCEL_REQUESTED, savedOrder?.status)
        assertEquals(true, savedOrder?.awaitingReturn)
        verify(exactly = 0) { orderKafkaProducer.publishCancelReservation(any(), any()) }
        verify(exactly = 1) { orderKafkaProducer.publishRefundRequested("corr-1", 1, any(), any()) }
    }

    @Test
    fun `onShipmentCancelled - before pickup also cancels the reservation`() {
        val order = orderWithShipment()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentCancelled(1, "corr-1")

        assertNotEquals(true, savedOrder?.awaitingReturn)
        verify(exactly = 1) { orderKafkaProducer.publishCancelReservation("corr-1", 1) }
        verify(exactly = 1) { orderKafkaProducer.publishRefundRequested("corr-1", 1, any(), any()) }
    }

    @Test
    fun `onShipmentLost - requests a refund without touching the reservation`() {
        val order = orderInDelivery()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentLost(1, "corr-1")

        assertEquals(OrderStatus.CANCEL_REQUESTED, savedOrder?.status)
        assertNotEquals(true, savedOrder?.awaitingReturn)
        verify(exactly = 0) { orderKafkaProducer.publishCancelReservation(any(), any()) }
        verify(exactly = 1) { orderKafkaProducer.publishRefundRequested("corr-1", 1, any(), any()) }
    }

    @Test
    fun `onShipmentReturnedToInventory - publishes a restock command with the order's items`() {
        val order = orderInDelivery().cancelShipment()
        stubGetOrder(order)
        stubSave()

        saga.onShipmentReturnedToInventory(1, "corr-1")

        verify(exactly = 1) {
            orderKafkaProducer.publishRestock("corr-1", 1, listOf(RestockItemDto(testItem().productId, testItem().quantity)))
        }
    }

    @Test
    fun `onRefundCompleted - confirms the refund with the reported amount and currency`() {
        val order = orderInDelivery().cancelShipment().requestRefund()
        stubGetOrder(order)
        stubSave()

        saga.onRefundCompleted(RefundCompletedEvent(1, BigDecimal("20.00"), "USD"), "corr-1")

        assertEquals(BigDecimal("20.00"), savedOrder?.refund?.amount?.amount)
        assertEquals(true, savedOrder?.refund?.isConfirmed)
    }

    @Test
    fun `onRefundFailed - flags the refund as failed`() {
        val order = orderInDelivery().cancelShipment().requestRefund()
        stubGetOrder(order)
        stubSave()

        saga.onRefundFailed(RefundFailedEvent(1), "corr-1")

        assertNotNull(savedOrder?.refund?.failedAt)
    }
}
