package com.hartmann.entities.model

import com.hartmann.entities.model.exception.InconsistentOrderException
import com.hartmann.entities.model.exception.OrderInvariantViolatedException
import com.hartmann.entities.model.exception.OrderNotCancellableException
import com.hartmann.entities.model.exception.OrderTransitionNotAllowedException
import com.hartmann.entities.model.exception.RefundAmountMismatchException
import com.hartmann.entities.model.exception.RefundNotRequestedException
import com.hartmann.entities.model.exception.ReturnNotAwaitedException
import com.hartmann.entities.model.exception.ShipmentNotCreatedException
import com.hartmann.entities.model.exception.UnrequestedShipmentCancellationException
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.ReservedItem
import com.hartmann.entities.value.ShipmentCancellationReason
import com.hartmann.entities.value.exception.ShipmentTransitionNotAllowedException
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
        productName = "Keyboard",
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

private fun testReservedItem() = ReservedItem(1, "Keyboard", 1, Money(BigDecimal("10.00"), Currency.EUR))

private fun newOrder(customerId: UUID): Order = Order.createNew(
    id = 1,
    customerId = customerId,
    reservedItems = listOf(testReservedItem()),
    address = testAddress(),
)

private fun processingOrder(id: Long = 1) = placedOrder(id = id).apply { confirmPayment() }

private fun orderWithShipment(shipmentId: Long = 5L) = processingOrder().apply { createShipment(shipmentId) }

private fun orderInDelivery() = orderWithShipment().apply { startDelivery() }

// the courier couldn't hand the goods over
private fun cancelRequestedAfterPickup() =
    orderInDelivery().apply { cancelShipment(ShipmentCancellationReason.RECIPIENT_REFUSED) }

// only the customer can cancel before pickup: no courier has the shipment yet
private fun cancelRequestedBeforePickup() = orderWithShipment().apply {
    requestCancellation()
    cancelShipment(ShipmentCancellationReason.ORDER_CANCELLED)
}

// the customer asked to cancel, and shipping-service hasn't answered yet: nothing is refunded so far
private fun awaitingShipmentCancellation(order: Order = orderWithShipment()) = order.apply { requestCancellation() }

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderTest {

    // --- constructor ---

    @Test
    fun `createExisting - reports an empty item list as an inconsistent order`() {
        assertThrows<InconsistentOrderException> { placedOrder(items = emptyList()) }
    }

    @Test
    fun `createExisting - reports items with mismatched currencies as an inconsistent order`() {
        assertThrows<InconsistentOrderException> {
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
    fun `createNew - builds a PLACED order under the given id, with an item for every reserved line`() {
        val orderId = 7L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val firstItem = ReservedItem(11, "Keyboard", 2, Money(BigDecimal("10.00"), Currency.EUR))
        val secondItem = ReservedItem(12, "Mouse", 4, Money(BigDecimal("2.50"), Currency.EUR))

        val order = Order.createNew(
            id = orderId,
            customerId = customerId,
            reservedItems = listOf(firstItem, secondItem),
            address = testAddress(),
        )

        assertEquals(orderId, order.id)
        assertEquals(customerId, order.customerId)
        assertEquals(OrderStatus.PLACED, order.status)
        assertNotNull(order.createdAt)
        assertEquals(listOf(firstItem.productId, secondItem.productId), order.items.map { it.productId })
        assertEquals(listOf(firstItem.name, secondItem.name), order.items.map { it.productName })
        assertEquals(listOf(firstItem.quantity, secondItem.quantity), order.items.map { it.quantity })
        assertEquals(listOf(firstItem.price, secondItem.price), order.items.map { it.price })
        assertEquals(Money(BigDecimal("30.00"), Currency.EUR), order.totalPrice())
    }

    @Test
    fun `createNew - rejects an order without an id`() {
        assertThrows<OrderInvariantViolatedException> {
            Order.createNew(
                id = 0,
                customerId = UUID.randomUUID(),
                reservedItems = listOf(testReservedItem()),
                address = testAddress(),
            )
        }
    }

    @Test
    fun `createNew - rejects an order without items`() {
        assertThrows<OrderInvariantViolatedException> {
            Order.createNew(
                id = 1,
                customerId = UUID.randomUUID(),
                reservedItems = emptyList(),
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
        assertThrows<InconsistentOrderException> {
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
        assertThrows<OrderTransitionNotAllowedException> { processingOrder().apply { failPayment() } }
    }

    @Test
    fun `expirePayment - cancels a placed order`() {
        val order = placedOrder().apply { expirePayment() }

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertEquals(CancellationReason.PAYMENT_EXPIRED, order.cancellationReason)
    }

    @Test
    fun `expirePayment - throws when order is not placed`() {
        assertThrows<OrderTransitionNotAllowedException> { processingOrder().apply { expirePayment() } }
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
        assertThrows<OrderTransitionNotAllowedException> { processingOrder().apply { confirmPayment() } }
    }

    // --- requestCancellation ---

    @Test
    fun `requestCancellation - moves a processing order to cancel-requested by the customer, refunding nothing yet`() {
        val order = orderWithShipment()

        val changed = order.requestCancellation()

        assertTrue(changed)
        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.CANCELLED_BY_CUSTOMER, order.cancellationReason)
        assertNull(order.refund)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `requestCancellation - changes nothing when the cancellation was already requested`() {
        val order = awaitingShipmentCancellation()

        val changed = order.requestCancellation()

        assertFalse(changed)
        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertNull(order.refund)
    }

    @Test
    fun `requestCancellation - changes nothing when the order is already being cancelled for another reason`() {
        val order = orderInDelivery().apply { loseShipment() }

        val changed = order.requestCancellation()

        assertFalse(changed)
        assertEquals(CancellationReason.SHIPMENT_LOST, order.cancellationReason)
    }

    @Test
    fun `requestCancellation - rejects an order that is not paid yet, leaving it unchanged`() {
        val order = placedOrder()

        assertThrows<OrderNotCancellableException> { order.requestCancellation() }

        assertEquals(OrderStatus.PLACED, order.status)
        assertNull(order.cancellationReason)
    }

    @Test
    fun `requestCancellation - rejects a fulfilled order`() {
        val order = orderInDelivery().apply { deliver() }

        assertThrows<OrderNotCancellableException> { order.requestCancellation() }

        assertEquals(OrderStatus.FULFILLED, order.status)
    }

    @Test
    fun `requestCancellation - rejects a cancelled order`() {
        val order = placedOrder().apply { failPayment() }

        assertThrows<OrderNotCancellableException> { order.requestCancellation() }

        assertEquals(CancellationReason.PAYMENT_FAILED, order.cancellationReason)
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
    fun `createShipment - attaches shipment info while the requested cancellation awaits shipping`() {
        val shipmentId = 5L
        val order = awaitingShipmentCancellation(processingOrder())

        order.createShipment(shipmentId)

        assertEquals(shipmentId, order.shipment?.shipmentId)
        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
    }

    @Test
    fun `createShipment - throws when order is not processing`() {
        assertThrows<OrderTransitionNotAllowedException> { placedOrder().apply { createShipment(5L) } }
    }

    @Test
    fun `createShipment - throws once the cancellation is confirmed`() {
        assertThrows<OrderTransitionNotAllowedException> { cancelRequestedBeforePickup().apply { createShipment(6L) } }
    }

    @Test
    fun `startDelivery - marks the shipment picked up`() {
        val order = orderInDelivery()

        assertNotNull(order.shipment?.pickedUpAt)
        assertTrue(order.wasPickedUp())
    }

    @Test
    fun `startDelivery - marks the shipment picked up while the requested cancellation awaits shipping`() {
        val order = awaitingShipmentCancellation().apply { startDelivery() }

        assertTrue(order.wasPickedUp())
        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
    }

    @Test
    fun `startDelivery - throws when order has no shipment`() {
        assertThrows<ShipmentNotCreatedException> { processingOrder().apply { startDelivery() } }
    }

    @Test
    fun `startDelivery - throws when order is not processing`() {
        assertThrows<OrderTransitionNotAllowedException> { placedOrder().apply { startDelivery() } }
    }

    @Test
    fun `startDelivery - throws once the cancellation is confirmed`() {
        assertThrows<OrderTransitionNotAllowedException> { cancelRequestedBeforePickup().apply { startDelivery() } }
    }

    @Test
    fun `deliver - fulfills the order and marks the shipment delivered`() {
        val order = orderInDelivery().apply { deliver() }

        assertEquals(OrderStatus.FULFILLED, order.status)
        assertNotNull(order.shipment?.deliveredAt)
    }

    @Test
    fun `deliver - fulfills the order and drops the cancellation the customer requested too late`() {
        val order = awaitingShipmentCancellation(orderInDelivery()).apply { deliver() }

        assertEquals(OrderStatus.FULFILLED, order.status)
        assertNull(order.cancellationReason)
        assertNull(order.refund)
    }

    @Test
    fun `deliver - throws when the shipment was never picked up`() {
        assertThrows<ShipmentTransitionNotAllowedException> { orderWithShipment().apply { deliver() } }
    }

    @Test
    fun `deliver - throws once the cancellation is confirmed`() {
        assertThrows<OrderTransitionNotAllowedException> { cancelRequestedAfterPickup().apply { deliver() } }
    }

    // --- loseShipment / cancelShipment (-> CANCEL_REQUESTED with a refund requested) ---

    @Test
    fun `loseShipment - does not await a return even though the goods were already picked up`() {
        val order = orderInDelivery().apply { loseShipment() }

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.SHIPMENT_LOST, order.cancellationReason)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `loseShipment - cancels the order as lost even though the customer had asked to cancel it`() {
        val order = awaitingShipmentCancellation(orderInDelivery()).apply { loseShipment() }

        assertEquals(CancellationReason.SHIPMENT_LOST, order.cancellationReason)
        assertNotNull(order.refund)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `loseShipment - throws when order is not processing`() {
        assertThrows<OrderTransitionNotAllowedException> { placedOrder().apply { loseShipment() } }
    }

    @Test
    fun `loseShipment - throws once the cancellation is confirmed`() {
        assertThrows<OrderTransitionNotAllowedException> { cancelRequestedAfterPickup().apply { loseShipment() } }
    }

    @Test
    fun `cancelShipment - cancels by the customer and awaits a return when the recipient refused the goods`() {
        val order = orderInDelivery().apply { cancelShipment(ShipmentCancellationReason.RECIPIENT_REFUSED) }

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.CANCELLED_BY_CUSTOMER, order.cancellationReason)
        assertTrue(order.awaitingReturn)
        assertNotNull(order.refund)
    }

    @Test
    fun `cancelShipment - cancels as a failed delivery when the recipient was unavailable`() {
        val order = orderInDelivery().apply { cancelShipment(ShipmentCancellationReason.RECIPIENT_UNAVAILABLE) }

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.DELIVERY_FAILED, order.cancellationReason)
        assertTrue(order.awaitingReturn)
        assertNotNull(order.refund)
    }

    @Test
    fun `cancelShipment - rejects a cancellation for the order when nobody asked to cancel it, leaving it unchanged`() {
        val order = orderInDelivery()

        assertThrows<UnrequestedShipmentCancellationException> { order.cancelShipment(ShipmentCancellationReason.ORDER_CANCELLED) }

        assertEquals(OrderStatus.PROCESSING, order.status)
        assertNull(order.cancellationReason)
        assertNull(order.refund)
    }

    @Test
    fun `cancelShipment - confirms the cancellation the customer requested, whatever the shipment was cancelled for`() {
        val order = awaitingShipmentCancellation(orderInDelivery())

        order.cancelShipment(ShipmentCancellationReason.RECIPIENT_UNAVAILABLE)

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertEquals(CancellationReason.CANCELLED_BY_CUSTOMER, order.cancellationReason)
        assertTrue(order.awaitingReturn)
        assertNotNull(order.refund)
    }

    @Test
    fun `cancelShipment - does not await a return when the courier has not picked up yet`() {
        val order = cancelRequestedBeforePickup()

        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `cancelShipment - throws when order is not processing`() {
        assertThrows<OrderTransitionNotAllowedException> {
            placedOrder().apply { cancelShipment(ShipmentCancellationReason.RECIPIENT_REFUSED) }
        }
    }

    @Test
    fun `cancelShipment - throws once the cancellation is confirmed`() {
        assertThrows<OrderTransitionNotAllowedException> {
            cancelRequestedAfterPickup().apply { cancelShipment(ShipmentCancellationReason.ORDER_CANCELLED) }
        }
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
        assertThrows<RefundNotRequestedException> { awaitingShipmentCancellation().apply { markRefundFailed() } }
    }

    // --- confirmRefund ---

    @Test
    fun `confirmRefund - finalizes to cancelled while the goods are still on their way back to the warehouse`() {
        val order = cancelRequestedAfterPickup()

        order.confirmRefund(Money(BigDecimal("10.00"), Currency.EUR))

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertTrue(order.refund!!.isConfirmed)
        // the trip back is logistics: still awaited, just no longer holding the order back
        assertTrue(order.awaitingReturn)
    }

    @Test
    fun `confirmRefund - finalizes to cancelled when the goods already returned`() {
        val order = cancelRequestedAfterPickup().apply { returnToStock() }

        order.confirmRefund(Money(BigDecimal("10.00"), Currency.EUR))

        assertEquals(OrderStatus.CANCELLED, order.status)
    }

    @Test
    fun `confirmRefund - finalizes to cancelled when there is no return to wait for`() {
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

        assertThrows<RefundAmountMismatchException> { order.confirmRefund(Money(BigDecimal("9.99"), Currency.EUR)) }

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertFalse(order.refund!!.isConfirmed)
    }

    @Test
    fun `confirmRefund - throws when there is no refund request`() {
        assertThrows<RefundNotRequestedException> {
            awaitingShipmentCancellation().apply { confirmRefund(Money(BigDecimal("10.00"), Currency.EUR)) }
        }
    }

    @Test
    fun `confirmRefund - throws when order is not cancel-requested`() {
        assertThrows<OrderTransitionNotAllowedException> {
            placedOrder().apply { confirmRefund(Money(BigDecimal("10.00"), Currency.EUR)) }
        }
    }

    // --- returnToStock ---

    @Test
    fun `returnToStock - records the goods back in stock, leaving a cancellation still awaiting its refund as it is`() {
        val order = cancelRequestedAfterPickup()

        order.returnToStock()

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.status)
        assertNotNull(order.shipment?.returnedAt)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `returnToStock - records the goods back in stock, leaving an already cancelled order cancelled`() {
        val order = cancelRequestedAfterPickup().apply { confirmRefund(Money(BigDecimal("10.00"), Currency.EUR)) }

        order.returnToStock()

        assertEquals(OrderStatus.CANCELLED, order.status)
        assertNotNull(order.shipment?.returnedAt)
        assertFalse(order.awaitingReturn)
    }

    @Test
    fun `returnToStock - throws when the courier never had the goods, leaving the order unchanged`() {
        val order = cancelRequestedBeforePickup()

        assertThrows<ReturnNotAwaitedException> { order.returnToStock() }

        assertNull(order.shipment?.returnedAt)
    }

    @Test
    fun `returnToStock - throws when the goods were already returned, leaving the order unchanged`() {
        val order = cancelRequestedAfterPickup().apply { returnToStock() }
        val returnedAt = order.shipment?.returnedAt

        assertThrows<ReturnNotAwaitedException> { order.returnToStock() }

        assertEquals(returnedAt, order.shipment?.returnedAt)
    }

    @Test
    fun `returnToStock - throws when the order was never cancelled`() {
        assertThrows<ReturnNotAwaitedException> { orderInDelivery().apply { returnToStock() } }
    }

    @Test
    fun `returnToStock - throws when a stored order awaits the goods but has no shipment`() {
        val order = Order.createExisting(
            id = 1,
            customerId = UUID.randomUUID(),
            items = listOf(testItem()),
            address = testAddress(),
            status = OrderStatus.CANCEL_REQUESTED,
            awaitingReturn = true,
            createdAt = Instant.now(),
        )

        assertThrows<ShipmentNotCreatedException> { order.returnToStock() }
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
