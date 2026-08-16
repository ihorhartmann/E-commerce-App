package com.hartmann.entities.model

import com.hartmann.entities.exception.DomainException
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

private fun testItem(quantity: Int = 1, amount: String = "10.00", currency: Currency = Currency.USD) =
    OrderItem.createExisting(id = 1, orderId = 1, productId = 1, quantity = quantity, price = Money(BigDecimal(amount), currency))

private fun placedOrder(items: List<OrderItem> = listOf(testItem()), id: Long = 0): Order =
    if (id == 0L) {
        Order.createNew(userId = 1, items = items, address = testAddress())
    } else {
        Order.createExisting(id = id, userId = 1, items = items, address = testAddress(), status = OrderStatus.PLACED, createdAt = LocalDateTime.now())
    }

private fun processingOrder(id: Long = 0) = placedOrder(id = id).confirmPayment()

private fun orderWithShipment(trackingNumber: String = "TRACK-1") = processingOrder().createShipment(trackingNumber)

private fun orderInDelivery() = orderWithShipment().startDelivery()

private fun cancelRequestedAfterPickup() = orderInDelivery().cancelShipment()

private fun cancelRequestedBeforePickup() = orderWithShipment().cancelShipment()

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderTest {

    // --- constructor ---

    @Test
    fun `constructor - rejects an empty item list`() {
        assertFailsWith<IllegalArgumentException> { placedOrder(items = emptyList()) }
    }

    @Test
    fun `constructor - rejects items with mismatched currencies`() {
        assertFailsWith<IllegalArgumentException> {
            placedOrder(
                items = listOf(
                    testItem(currency = Currency.USD),
                    testItem(currency = Currency.EUR),
                ),
            )
        }
    }

    // --- createNew ---

    @Test
    fun `createNew - builds a new order in PLACED status with no id yet`() {
        val order = Order.createNew(userId = 1, items = listOf(testItem()), address = testAddress())

        assertEquals(0L, order.id)
        assertEquals(1L, order.userId)
        assertEquals(OrderStatus.PLACED, order.status)
        assertNotNull(order.createdAt)
    }

    // --- createExisting ---

    @Test
    fun `createExisting - builds an order with the given state`() {
        val id = 42L
        val order = Order.createExisting(
            id = id,
            userId = 1,
            items = listOf(testItem()),
            address = testAddress(),
            status = OrderStatus.PROCESSING,
            createdAt = LocalDateTime.now(),
        )

        assertEquals(id, order.id)
        assertEquals(OrderStatus.PROCESSING, order.status)
    }

    @Test
    fun `createExisting - throws when id is not positive`() {
        assertFailsWith<IllegalArgumentException> {
            Order.createExisting(
                id = 0,
                userId = 1,
                items = listOf(testItem()),
                address = testAddress(),
                status = OrderStatus.PLACED,
                createdAt = LocalDateTime.now(),
            )
        }
    }

    // --- totalPrice ---

    @Test
    fun `totalPrice - sums item prices in the same currency`() {
        val order = placedOrder(
            items = listOf(
                testItem(quantity = 2, amount = "10.00"),
                testItem(quantity = 1, amount = "5.00"),
            ),
        )

        assertEquals(Money(BigDecimal("25.00"), Currency.USD), order.totalPrice())
    }

    // --- failReservation / outOfStock / failPayment (PLACED -> CANCELLED, no refund) ---

    @Test
    fun `failReservation - cancels a placed order without a refund`() {
        val order = placedOrder().failReservation()

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertEquals(CancellationReason.RESERVATION_FAILED, order.cancellationReason)
        assertNull(order.refund)
    }

    @Test
    fun `failReservation - throws when order is not placed`() {
        assertFailsWith<DomainException> { processingOrder().failReservation() }
    }

    @Test
    fun `outOfStock - cancels a placed order`() {
        val order = placedOrder().outOfStock()

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertEquals(CancellationReason.OUT_OF_STOCK, order.cancellationReason)
    }

    @Test
    fun `outOfStock - throws when order is not placed`() {
        assertFailsWith<DomainException> { processingOrder().outOfStock() }
    }

    @Test
    fun `failPayment - cancels a placed order`() {
        val order = placedOrder().failPayment()

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertEquals(CancellationReason.PAYMENT_FAILED, order.cancellationReason)
    }

    @Test
    fun `failPayment - throws when order is not placed`() {
        assertFailsWith<DomainException> { processingOrder().failPayment() }
    }

    // --- confirmPayment ---

    @Test
    fun `confirmPayment - moves a placed order to processing`() {
        val order = placedOrder().confirmPayment()

        assertEquals(OrderStatus.PROCESSING, order.status)
        assertNotNull(order.paymentConfirmedAt)
    }

    @Test
    fun `confirmPayment - throws when order is not placed`() {
        assertFailsWith<DomainException> { processingOrder().confirmPayment() }
    }

    // --- createShipment / startDelivery / deliver ---

    @Test
    fun `createShipment - attaches shipment info to a processing order`() {
        val trackingNumber = "TRACK-1"
        val order = orderWithShipment(trackingNumber)

        assertEquals(trackingNumber, order.shipment?.trackingNumber)
        assertNull(order.shipment?.pickedUpAt)
    }

    @Test
    fun `createShipment - throws when order is not processing`() {
        assertFailsWith<DomainException> { placedOrder().createShipment("TRACK-1") }
    }

    @Test
    fun `startDelivery - marks the shipment picked up`() {
        val order = orderInDelivery()

        assertNotNull(order.shipment?.pickedUpAt)
        assertTrue(order.wasPickedUp())
    }

    @Test
    fun `startDelivery - throws when order has no shipment`() {
        assertFailsWith<IllegalArgumentException> { processingOrder().startDelivery() }
    }

    @Test
    fun `startDelivery - throws when order is not processing`() {
        assertFailsWith<DomainException> { placedOrder().startDelivery() }
    }

    @Test
    fun `deliver - fulfills the order and marks the shipment delivered`() {
        val order = orderInDelivery().deliver()

        assertEquals(OrderStatus.FULFILLED, order.status)
        assertNotNull(order.shipment?.deliveredAt)
    }

    @Test
    fun `deliver - throws when the shipment was never picked up`() {
        assertFailsWith<IllegalStateException> { orderWithShipment().deliver() }
    }

    // --- failShipmentCreation / loseShipment / cancelShipment (PROCESSING -> CANCEL_REQUESTED) ---

    @Test
    fun `failShipmentCreation - requests cancellation without awaiting a return`() {
        val order = processingOrder().failShipmentCreation()

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.SHIPMENT_CREATION_FAILED, order.cancellationReason)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `loseShipment - does not await a return even though the goods were already picked up`() {
        val order = orderInDelivery().loseShipment()

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.SHIPMENT_LOST, order.cancellationReason)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `loseShipment - throws when order is not processing`() {
        assertFailsWith<DomainException> { placedOrder().loseShipment() }
    }

    @Test
    fun `cancelShipment - awaits a return when the courier already picked up the goods`() {
        val order = cancelRequestedAfterPickup()

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.CANCELLED_BY_CUSTOMER, order.cancellationReason)
        assertTrue(order.awaitingReturn)
    }

    @Test
    fun `cancelShipment - does not await a return when the courier has not picked up yet`() {
        val order = cancelRequestedBeforePickup()

        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `cancelShipment - throws when order is not processing`() {
        assertFailsWith<DomainException> { placedOrder().cancelShipment() }
    }

    // --- requestRefund / markRefundFailed ---

    @Test
    fun `requestRefund - attaches a refund request`() {
        val order = cancelRequestedAfterPickup().requestRefund()

        assertNotNull(order.refund)
        assertFalse(order.refund.isConfirmed)
    }

    @Test
    fun `requestRefund - throws when order is not cancel-requested`() {
        assertFailsWith<DomainException> { placedOrder().requestRefund() }
    }

    @Test
    fun `markRefundFailed - flags the refund without changing order status`() {
        val order = cancelRequestedAfterPickup().requestRefund().markRefundFailed()

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertNotNull(order.refund?.failedAt)
    }

    @Test
    fun `markRefundFailed - throws when there is no refund request`() {
        assertFailsWith<IllegalArgumentException> { cancelRequestedAfterPickup().markRefundFailed() }
    }

    // --- confirmRefund / returnToStock, both orderings must finalize the same way ---

    @Test
    fun `returnToStock - finalizes to cancelled when the refund was already confirmed`() {
        val afterRefund = cancelRequestedAfterPickup()
            .requestRefund()
            .confirmRefund(Money(BigDecimal("10.00"), Currency.USD))

        assertEquals(OrderStatus.CANCEL_REQUESTED, afterRefund.status)

        val afterReturn = afterRefund.returnToStock()

        assertEquals(OrderStatus.CANCELLED, afterReturn.status)
        assertNotNull(afterReturn.shipment?.returnedAt)
    }

    @Test
    fun `confirmRefund - finalizes to cancelled when the goods already returned`() {
        val afterReturn = cancelRequestedAfterPickup()
            .requestRefund()
            .returnToStock()

        assertEquals(OrderStatus.CANCEL_REQUESTED, afterReturn.status)
        assertFalse(afterReturn.awaitingReturn)

        val afterRefund = afterReturn.confirmRefund(Money(BigDecimal("10.00"), Currency.USD))

        assertEquals(OrderStatus.CANCELLED, afterRefund.status)
    }

    @Test
    fun `confirmRefund - finalizes to cancelled immediately when there is no return to wait for`() {
        val order = cancelRequestedBeforePickup()
            .requestRefund()
            .confirmRefund(Money(BigDecimal("10.00"), Currency.USD))

        assertEquals(OrderStatus.CANCELLED, order.status)
    }

    @Test
    fun `confirmRefund - throws when there is no refund request`() {
        assertFailsWith<IllegalArgumentException> {
            cancelRequestedAfterPickup().confirmRefund(Money(BigDecimal("10.00"), Currency.USD))
        }
    }

    @Test
    fun `confirmRefund - throws when order is not cancel-requested`() {
        assertFailsWith<DomainException> {
            placedOrder().confirmRefund(Money(BigDecimal("10.00"), Currency.USD))
        }
    }

    @Test
    fun `returnToStock - throws when order has no shipment`() {
        val order = processingOrder().failShipmentCreation()

        assertFailsWith<IllegalArgumentException> { order.returnToStock() }
    }

    @Test
    fun `returnToStock - throws when order is not cancel-requested`() {
        assertFailsWith<DomainException> { placedOrder().returnToStock() }
    }

    // --- wasPickedUp ---

    @Test
    fun `wasPickedUp - is false before a shipment exists`() {
        assertFalse(processingOrder().wasPickedUp())
    }

    @Test
    fun `wasPickedUp - is false before pickup and true after`() {
        assertFalse(orderWithShipment().wasPickedUp())
        assertTrue(orderInDelivery().wasPickedUp())
    }

    // --- equals ---

    @Test
    fun `equals - returns true for orders with the same id`() {
        val id = 1L
        val a = placedOrder(id = id)
        val b = processingOrder(id = id)

        assertEquals(a, b)
    }

    @Test
    fun `equals - returns false for orders with different ids`() {
        val a = placedOrder(id = 1)
        val b = placedOrder(id = 2)

        assertNotEquals(a, b)
    }

    @Test
    fun `equals - returns false when both orders are not yet persisted`() {
        val a = placedOrder()
        val b = placedOrder()

        assertNotEquals(a, b)
    }

    @Test
    fun `equals - returns false for an object of a different type`() {
        val order = placedOrder(id = 1)

        assertNotEquals<Any>(order, "not an order")
    }
}
