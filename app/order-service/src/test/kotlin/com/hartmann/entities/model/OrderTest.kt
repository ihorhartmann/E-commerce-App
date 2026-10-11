package com.hartmann.entities.model

import com.hartmann.entities.exception.DomainException
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RequestedItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

private fun testItem(quantity: Int = 1, amount: String = "10.00", currency: Currency = Currency.EUR) =
    OrderItem.createExisting(
        id = 1,
        orderId = 1,
        productId = 1,
        quantity = quantity,
        price = Money(BigDecimal(amount), currency)
    )

private fun placedOrder(items: List<OrderItem> = listOf(testItem()), id: Long = 1): Order =
    Order.createExisting(
        id = id,
        customerId = UUID.randomUUID(),
        items = items,
        address = testAddress(),
        status = OrderStatus.PLACED,
        createdAt = Instant.now()
    )

private fun newOrder(customerId: UUID): Order = Order.createNew(
    id = 1,
    customerId = customerId,
    requestedItems = listOf(RequestedItem(productId = 1, quantity = 1)),
    prices = mapOf(1L to Money(BigDecimal("10.00"), Currency.EUR)),
    address = testAddress(),
)

private fun processingOrder(id: Long = 1) = placedOrder(id = id).apply { confirmPayment() }

private fun orderWithShipment(shipmentId: Long = 5L) = processingOrder().apply { createShipment(shipmentId) }

private fun orderInDelivery() = orderWithShipment().apply { startDelivery() }

private fun cancelRequestedAfterPickup() = orderInDelivery().apply { cancelShipment() }

private fun cancelRequestedBeforePickup() = orderWithShipment().apply { cancelShipment() }

// A CANCEL_REQUESTED order loaded without a refund request - no transition produces one anymore,
// but the model still has to guard against such a persisted state.
private fun cancelRequestedWithoutRefund() = Order.createExisting(
    id = 1,
    customerId = UUID.randomUUID(),
    items = listOf(testItem()),
    address = testAddress(),
    status = OrderStatus.CANCEL_REQUESTED,
    cancellationReason = CancellationReason.CANCELLED_BY_CUSTOMER,
    createdAt = Instant.now(),
)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderTest {

    // --- constructor ---

    @Test
    fun `constructor - rejects an empty item list`() {
        assertThrows<IllegalArgumentException> { placedOrder(items = emptyList()) }
    }

    @Test
    fun `constructor - rejects items with mismatched currencies`() {
        assertThrows<IllegalArgumentException> {
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
    fun `createNew - builds a PLACED order under the given id, with every requested item priced from the reservation`() {
        val orderId = 7L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val firstProductId = 11L
        val secondProductId = 12L
        val firstPrice = Money(BigDecimal("10.00"), Currency.EUR)
        val secondPrice = Money(BigDecimal("2.50"), Currency.EUR)

        val order = Order.createNew(
            id = orderId,
            customerId = customerId,
            requestedItems = listOf(RequestedItem(firstProductId, 2), RequestedItem(secondProductId, 4)),
            prices = mapOf(firstProductId to firstPrice, secondProductId to secondPrice),
            address = testAddress(),
        )

        assertEquals(orderId, order.id)
        assertEquals(customerId, order.customerId)
        assertEquals(OrderStatus.PLACED, order.status)
        assertNotNull(order.createdAt)
        assertEquals(listOf(firstProductId, secondProductId), order.items.map { it.productId })
        assertEquals(listOf(firstPrice, secondPrice), order.items.map { it.price })
        assertEquals(Money(BigDecimal("30.00"), Currency.EUR), order.totalPrice())
    }

    @Test
    fun `createNew - rejects a requested item whose product the reservation gave no price for`() {
        assertThrows<DomainException> {
            Order.createNew(
                id = 7,
                customerId = UUID.randomUUID(),
                requestedItems = listOf(RequestedItem(productId = 11, quantity = 1)),
                prices = mapOf(12L to Money(BigDecimal("10.00"), Currency.EUR)),
                address = testAddress(),
            )
        }
    }

    @Test
    fun `createNew - rejects an order without an id`() {
        assertThrows<IllegalArgumentException> {
            Order.createNew(
                id = 0,
                customerId = UUID.randomUUID(),
                requestedItems = listOf(RequestedItem(productId = 11, quantity = 1)),
                prices = mapOf(11L to Money(BigDecimal("10.00"), Currency.EUR)),
                address = testAddress(),
            )
        }
    }

    // --- createExisting ---

    @Test
    fun `createExisting - builds an order with the given state`() {
        val id = 42L
        val version = 7L
        val order = Order.createExisting(
            id = id,
            customerId = UUID.randomUUID(),
            items = listOf(testItem()),
            address = testAddress(),
            status = OrderStatus.PROCESSING,
            version = version,
            createdAt = Instant.now(),
        )

        assertEquals(id, order.id)
        assertEquals(OrderStatus.PROCESSING, order.status)
        assertEquals(version, order.version)
    }

    @Test
    fun `createExisting - throws when id is not positive`() {
        assertThrows<IllegalArgumentException> {
            Order.createExisting(
                id = 0,
                customerId = UUID.randomUUID(),
                items = listOf(testItem()),
                address = testAddress(),
                status = OrderStatus.PLACED,
                createdAt = Instant.now(),
            )
        }
    }

    // --- isOwnedBy ---

    @Test
    fun `isOwnedBy - true for the customer who placed the order`() {
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = newOrder(customerId)

        assertTrue(order.isOwnedBy(customerId))
    }

    @Test
    fun `isOwnedBy - false for another customer`() {
        val order = newOrder(UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001"))

        assertFalse(order.isOwnedBy(UUID.fromString("0192f1d2-7c4b-7000-8000-000000000002")))
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

        assertEquals(Money(BigDecimal("25.00"), Currency.EUR), order.totalPrice())
    }

    // --- failPayment / expirePayment (PLACED -> CANCELLED, no refund) ---

    @Test
    fun `failPayment - cancels a placed order`() {
        val order = placedOrder().apply { failPayment() }

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertEquals(CancellationReason.PAYMENT_FAILED, order.cancellationReason)
    }

    @Test
    fun `failPayment - throws when order is not placed`() {
        assertThrows<DomainException> { processingOrder().apply { failPayment() } }
    }

    @Test
    fun `expirePayment - cancels a placed order`() {
        val order = placedOrder().apply { expirePayment() }

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertEquals(CancellationReason.PAYMENT_EXPIRED, order.cancellationReason)
    }

    @Test
    fun `expirePayment - throws when order is not placed`() {
        assertThrows<DomainException> { processingOrder().apply { expirePayment() } }
    }

    // --- confirmPayment ---

    @Test
    fun `confirmPayment - moves a placed order to processing`() {
        val order = placedOrder().apply { confirmPayment() }

        assertEquals(OrderStatus.PROCESSING, order.status)
        assertNotNull(order.paymentConfirmedAt)
    }

    @Test
    fun `confirmPayment - throws when order is not placed`() {
        assertThrows<DomainException> { processingOrder().apply { confirmPayment() } }
    }

    // --- createShipment / startDelivery / deliver ---

    @Test
    fun `createShipment - attaches shipment info to a processing order`() {
        val shipmentId = 5L
        val order = orderWithShipment(shipmentId)

        assertEquals(shipmentId, order.shipment?.shipmentId)
        assertNull(order.shipment?.pickedUpAt)
    }

    @Test
    fun `createShipment - throws when order is not processing`() {
        assertThrows<DomainException> { placedOrder().apply { createShipment(5L) } }
    }

    @Test
    fun `startDelivery - marks the shipment picked up`() {
        val order = orderInDelivery()

        assertNotNull(order.shipment?.pickedUpAt)
        assertTrue(order.wasPickedUp())
    }

    @Test
    fun `startDelivery - throws when order has no shipment`() {
        assertThrows<IllegalArgumentException> { processingOrder().apply { startDelivery() } }
    }

    @Test
    fun `startDelivery - throws when order is not processing`() {
        assertThrows<DomainException> { placedOrder().apply { startDelivery() } }
    }

    @Test
    fun `deliver - fulfills the order and marks the shipment delivered`() {
        val order = orderInDelivery().apply { deliver() }

        assertEquals(OrderStatus.FULFILLED, order.status)
        assertNotNull(order.shipment?.deliveredAt)
    }

    @Test
    fun `deliver - throws when the shipment was never picked up`() {
        assertThrows<IllegalStateException> { orderWithShipment().apply { deliver() } }
    }

    // --- loseShipment / cancelShipment (PROCESSING -> CANCEL_REQUESTED) ---

    @Test
    fun `loseShipment - does not await a return even though the goods were already picked up`() {
        val order = orderInDelivery().apply { loseShipment() }

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.SHIPMENT_LOST, order.cancellationReason)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `loseShipment - throws when order is not processing`() {
        assertThrows<DomainException> { placedOrder().apply { loseShipment() } }
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
        assertThrows<DomainException> { placedOrder().apply { cancelShipment() } }
    }

    @Test
    fun `cancelShipment - requests a refund`() {
        val order = cancelRequestedBeforePickup()

        assertNotNull(order.refund)
        assertFalse(order.refund!!.isConfirmed)
    }

    @Test
    fun `loseShipment - requests a refund`() {
        val order = orderInDelivery().apply { loseShipment() }

        assertNotNull(order.refund)
        assertFalse(order.refund!!.isConfirmed)
    }

    // --- refundAmount ---

    @Test
    fun `refundAmount - is the order's total price`() {
        val order = placedOrder(
            items = listOf(
                testItem(quantity = 2, amount = "10.00"),
                testItem(quantity = 1, amount = "5.00"),
            ),
        )

        assertEquals(order.totalPrice(), order.refundAmount())
    }

    // --- requiresReservationRelease ---

    @Test
    fun `requiresReservationRelease - is false for an order that is not cancelled`() {
        assertFalse(orderWithShipment().requiresReservationRelease())
    }

    @Test
    fun `requiresReservationRelease - is true when cancelled before the courier picked up the goods`() {
        assertTrue(placedOrder().apply { failPayment() }.requiresReservationRelease())
        assertTrue(cancelRequestedBeforePickup().requiresReservationRelease())
    }

    @Test
    fun `requiresReservationRelease - is false when cancelled after the courier picked up the goods`() {
        assertFalse(cancelRequestedAfterPickup().requiresReservationRelease())
        assertFalse(orderInDelivery().apply { loseShipment() }.requiresReservationRelease())
    }

    // --- markRefundFailed ---

    @Test
    fun `markRefundFailed - flags the refund without changing order status`() {
        val order = cancelRequestedAfterPickup().apply { markRefundFailed() }

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertNotNull(order.refund?.failedAt)
    }

    @Test
    fun `markRefundFailed - throws when there is no refund request`() {
        assertThrows<IllegalArgumentException> { cancelRequestedWithoutRefund().apply { markRefundFailed() } }
    }

    // --- confirmRefund / returnToStock, both orderings must finalize the same way ---

    @Test
    fun `returnToStock - finalizes to cancelled when the refund was already confirmed`() {
        val order = cancelRequestedAfterPickup()
        order.confirmRefund(Money(BigDecimal("10.00"), Currency.EUR))

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)

        order.returnToStock()

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertNotNull(order.shipment?.returnedAt)
    }

    @Test
    fun `confirmRefund - finalizes to cancelled when the goods already returned`() {
        val order = cancelRequestedAfterPickup()
        order.returnToStock()

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertFalse(order.awaitingReturn)

        order.confirmRefund(Money(BigDecimal("10.00"), Currency.EUR))

        assertEquals(OrderStatus.CANCELLED, order.status)
    }

    @Test
    fun `confirmRefund - finalizes to cancelled immediately when there is no return to wait for`() {
        val order = cancelRequestedBeforePickup()
        order.confirmRefund(Money(BigDecimal("10.00"), Currency.EUR))

        assertEquals(OrderStatus.CANCELLED, order.status)
    }

    @Test
    fun `confirmRefund - accepts the requested amount at a different scale`() {
        val order = cancelRequestedBeforePickup().apply { confirmRefund(Money(BigDecimal("10"), Currency.EUR)) }

        assertEquals(OrderStatus.CANCELLED, order.status)
    }

    @Test
    fun `confirmRefund - throws when the refunded amount differs from the requested one, leaving the order unchanged`() {
        val order = cancelRequestedBeforePickup()

        assertThrows<DomainException> { order.confirmRefund(Money(BigDecimal("9.99"), Currency.EUR)) }

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertFalse(order.refund!!.isConfirmed)
    }

    @Test
    fun `confirmRefund - throws when there is no refund request`() {
        assertThrows<IllegalArgumentException> {
            cancelRequestedWithoutRefund().apply { confirmRefund(Money(BigDecimal("10.00"), Currency.EUR)) }
        }
    }

    @Test
    fun `confirmRefund - throws when order is not cancel-requested`() {
        assertThrows<DomainException> {
            placedOrder().apply { confirmRefund(Money(BigDecimal("10.00"), Currency.EUR)) }
        }
    }

    @Test
    fun `returnToStock - throws when order has no shipment`() {
        val order = processingOrder().apply { cancelShipment() }

        assertThrows<IllegalArgumentException> { order.returnToStock() }
    }

    @Test
    fun `returnToStock - throws when order is not cancel-requested`() {
        assertThrows<DomainException> { placedOrder().apply { returnToStock() } }
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
}
