package com.hartmann.feature.order

import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.model.Money
import com.hartmann.core.model.Page
import com.hartmann.core.notification.Notifier
import com.hartmann.feature.order.exception.OrderAlreadyPaidException
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderStatus
import com.hartmann.feature.order.model.OrderSummary
import dev.mokkery.*
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentially
import dev.mokkery.answering.throws
import dev.mokkery.matcher.any
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verify.VerifyMode.Companion.not
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

// the ViewModel's fixed page size and polling interval - never what a test is about
private const val PAGE_SIZE = 20
private const val POLL_INTERVAL_MILLIS = 3_000L

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModelTest {
    private val orderApi = mock<OrderApi>()
    private val notifier = mock<Notifier>(MockMode.autoUnit)
    private val errorHandler = mock<ErrorHandler>(MockMode.autoUnit)

    private fun TestScope.ordersViewModel() = OrdersViewModel(backgroundScope, orderApi, notifier, errorHandler)

    private fun order(
        id: Long,
        status: OrderStatus,
        paymentConfirmedAt: String? = null,
        cancellationReason: String? = null,
    ) = Order(
        id = id,
        status = status,
        cancellationReason = cancellationReason,
        items = emptyList(),
        total = Money(7900, "EUR"),
        address = Address("Kyiv", "01001", "Khreshchatyk St, 1"),
        createdAt = "2026-10-14T10:00:00Z",
        paymentConfirmedAt = paymentConfirmedAt,
        shipment = null,
        refund = null,
    )

    private fun summary(id: Long, status: OrderStatus) = OrderSummary(id, status, Money(7900, "EUR"), 1, "2026-10-14T10:00:00Z")

    @Test
    fun `load - shows the requested page of orders`() = runTest {
        val pageNumber = 1
        val expected = Page(listOf(summary(42, OrderStatus.PROCESSING)), pageNumber, 2)
        everySuspend { orderApi.listOrders(pageNumber, PAGE_SIZE) } returns expected
        val viewModel = ordersViewModel()

        viewModel.load(pageNumber)
        runCurrent()

        assertEquals(expected, viewModel.state.value.page)
        assertFalse(viewModel.state.value.loading)
        verifySuspend(exactly(1)) { orderApi.listOrders(pageNumber, PAGE_SIZE) }
    }

    @Test
    fun `load - hands a failure to the error handler and stops loading`() = runTest {
        val error = IllegalStateException("down")
        everySuspend { orderApi.listOrders(any(), any()) } throws error
        val viewModel = ordersViewModel()

        viewModel.load(0)
        runCurrent()

        assertFalse(viewModel.state.value.loading)
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `toggle - expands a collapsed order and loads its details`() = runTest {
        val orderId = 42L
        val expected = order(orderId, OrderStatus.FULFILLED)
        everySuspend { orderApi.getOrder(orderId) } returns expected
        val viewModel = ordersViewModel()

        viewModel.toggle(orderId)
        runCurrent()

        assertEquals(orderId, viewModel.state.value.expandedOrderId)
        assertEquals(expected, viewModel.state.value.expandedOrder)
        verifySuspend(exactly(1)) { orderApi.getOrder(orderId) }
    }

    @Test
    fun `toggle - collapses the expanded order and stops polling it`() = runTest {
        val orderId = 42L
        everySuspend { orderApi.getOrder(orderId) } returns order(orderId, OrderStatus.PROCESSING)
        val viewModel = ordersViewModel()
        viewModel.toggle(orderId)
        runCurrent()

        viewModel.toggle(orderId)
        advanceTimeBy((POLL_INTERVAL_MILLIS * 3).milliseconds)
        runCurrent()

        assertNull(viewModel.state.value.expandedOrderId)
        assertNull(viewModel.state.value.expandedOrder)
        verifySuspend(exactly(1)) { orderApi.getOrder(orderId) }
    }

    @Test
    fun `expand - polls the order until it reaches a final status, updating its status in the list`() = runTest {
        val orderId = 42L
        val firstPage = 0
        everySuspend { orderApi.listOrders(firstPage, PAGE_SIZE) } returns Page(listOf(summary(orderId, OrderStatus.PLACED)), firstPage, 1)
        everySuspend { orderApi.getOrder(orderId) } sequentially {
            returns(order(orderId, OrderStatus.PROCESSING))
            returns(order(orderId, OrderStatus.FULFILLED))
        }
        val viewModel = ordersViewModel()
        viewModel.load(firstPage)
        runCurrent()

        viewModel.expand(orderId)
        runCurrent()
        advanceTimeBy(POLL_INTERVAL_MILLIS.milliseconds)
        runCurrent()
        advanceTimeBy((POLL_INTERVAL_MILLIS * 3).milliseconds)
        runCurrent()

        assertEquals(OrderStatus.FULFILLED, viewModel.state.value.expandedOrder?.status)
        assertEquals(OrderStatus.FULFILLED, viewModel.state.value.page.items.single().status)
        verifySuspend(exactly(2)) { orderApi.getOrder(orderId) }
        // never seen unpaid: nothing to tell about its payment
        verify(not) { notifier.success(any()) }
    }

    @Test
    fun `expand - stops polling when the order can't be loaded`() = runTest {
        val error = IllegalStateException("down")
        val orderId = 42L
        everySuspend { orderApi.getOrder(orderId) } throws error
        val viewModel = ordersViewModel()

        viewModel.expand(orderId)
        runCurrent()
        advanceTimeBy((POLL_INTERVAL_MILLIS * 3).milliseconds)
        runCurrent()

        verifySuspend(exactly(1)) { orderApi.getOrder(orderId) }
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `cancel - cancels the order and keeps polling it until the cancellation completes`() = runTest {
        val orderId = 42L
        val paidAt = "2026-10-14T10:01:00Z"
        everySuspend { orderApi.getOrder(orderId) } sequentially {
            returns(order(orderId, OrderStatus.PROCESSING, paidAt))
            returns(order(orderId, OrderStatus.CANCELLED, paidAt))
        }
        everySuspend { orderApi.cancelOrder(orderId) } returns order(orderId, OrderStatus.CANCEL_REQUESTED, paidAt)
        val viewModel = ordersViewModel()
        viewModel.expand(orderId)
        runCurrent()

        viewModel.cancel(orderId)
        runCurrent()
        val statusRightAfterCancel = viewModel.state.value.expandedOrder?.status
        advanceTimeBy(POLL_INTERVAL_MILLIS.milliseconds)
        runCurrent()

        assertEquals(OrderStatus.CANCEL_REQUESTED, statusRightAfterCancel)
        assertEquals(OrderStatus.CANCELLED, viewModel.state.value.expandedOrder?.status)
        assertFalse(viewModel.state.value.cancelling)
        verifySuspend(exactly(1)) { orderApi.cancelOrder(orderId) }
        verifySuspend(exactly(2)) { orderApi.getOrder(orderId) }
        verify(exactly(1)) { notifier.success("Cancelling order #$orderId. The paid amount is refunded once it's done.") }
    }

    @Test
    fun `cancel - doesn't poll an order the cancellation already completed`() = runTest {
        val orderId = 42L
        everySuspend { orderApi.cancelOrder(orderId) } returns order(orderId, OrderStatus.CANCELLED)
        val viewModel = ordersViewModel()

        viewModel.cancel(orderId)
        runCurrent()
        advanceTimeBy((POLL_INTERVAL_MILLIS * 3).milliseconds)
        runCurrent()

        verifySuspend(exactly(1)) { orderApi.cancelOrder(orderId) }
        verifySuspend(not) { orderApi.getOrder(any()) }
    }

    @Test
    fun `cancel - hands a failure to the error handler`() = runTest {
        val error = IllegalStateException("not cancellable")
        everySuspend { orderApi.cancelOrder(any()) } throws error
        val viewModel = ordersViewModel()

        viewModel.cancel(42)
        runCurrent()

        assertFalse(viewModel.state.value.cancelling)
        verify(exactly(1)) { errorHandler.handle(error) }
        verify(not) { notifier.success(any()) }
    }

    @Test
    fun `expand - tells right away that the payment of an order just paid is confirmed, if it already is`() = runTest {
        val orderId = 42L
        everySuspend { orderApi.getOrder(orderId) } returns order(orderId, OrderStatus.PROCESSING, "2026-10-14T10:01:00Z")
        val viewModel = ordersViewModel()

        viewModel.expand(orderId, awaitingPayment = true)
        runCurrent()

        verify(exactly(1)) { notifier.success("Payment for order #$orderId is confirmed.") }
    }

    @Test
    fun `expand - tells once polling sees the payment of an unpaid order confirmed`() = runTest {
        val orderId = 42L
        val paidAt = "2026-10-14T10:01:00Z"
        everySuspend { orderApi.getOrder(orderId) } sequentially {
            returns(order(orderId, OrderStatus.PLACED))
            returns(order(orderId, OrderStatus.PROCESSING, paidAt))
            returns(order(orderId, OrderStatus.FULFILLED, paidAt))
        }
        val viewModel = ordersViewModel()

        viewModel.expand(orderId)
        runCurrent()
        advanceTimeBy(POLL_INTERVAL_MILLIS.milliseconds)
        runCurrent()
        advanceTimeBy(POLL_INTERVAL_MILLIS.milliseconds)
        runCurrent()

        verify(exactly(1)) { notifier.success("Payment for order #$orderId is confirmed.") }
        verifySuspend(exactly(3)) { orderApi.getOrder(orderId) }
    }

    @Test
    fun `expand - tells once polling sees an unpaid order cancelled because its payment failed`() = runTest {
        val orderId = 42L
        everySuspend { orderApi.getOrder(orderId) } sequentially {
            returns(order(orderId, OrderStatus.PLACED))
            returns(order(orderId, OrderStatus.CANCELLED, cancellationReason = "PAYMENT_FAILED"))
        }
        val viewModel = ordersViewModel()

        viewModel.expand(orderId)
        runCurrent()
        advanceTimeBy(POLL_INTERVAL_MILLIS.milliseconds)
        runCurrent()

        verify(exactly(1)) {
            notifier.error(
                "Your payment for order #$orderId was declined, so the order was cancelled. You haven't been charged."
            )
        }
        verify(not) { notifier.success(any()) }
    }

    @Test
    fun `expand - tells once polling sees an unpaid order cancelled because it wasn't paid in time`() = runTest {
        val orderId = 42L
        everySuspend { orderApi.getOrder(orderId) } sequentially {
            returns(order(orderId, OrderStatus.PLACED))
            returns(order(orderId, OrderStatus.CANCELLED, cancellationReason = "PAYMENT_EXPIRED"))
        }
        val viewModel = ordersViewModel()

        viewModel.expand(orderId)
        runCurrent()
        advanceTimeBy(POLL_INTERVAL_MILLIS.milliseconds)
        runCurrent()

        verify(exactly(1)) {
            notifier.error("Order #$orderId was cancelled because it wasn't paid in time. You haven't been charged.")
        }
        verify(not) { notifier.success(any()) }
    }

    @Test
    fun `cancel - cancels an unpaid order and keeps polling it until it's cancelled, telling once it is`() = runTest {
        val orderId = 42L
        val cancellationReason = "CANCELLED_BY_CUSTOMER"
        everySuspend { orderApi.cancelOrder(orderId) } returns
            order(orderId, OrderStatus.CANCEL_REQUESTED, cancellationReason = cancellationReason)
        everySuspend { orderApi.getOrder(orderId) } sequentially {
            returns(order(orderId, OrderStatus.PLACED))
            returns(order(orderId, OrderStatus.CANCELLED, cancellationReason = cancellationReason))
        }
        val viewModel = ordersViewModel()
        viewModel.expand(orderId)
        runCurrent()

        viewModel.cancel(orderId)
        runCurrent()
        advanceTimeBy(POLL_INTERVAL_MILLIS.milliseconds)
        runCurrent()

        assertEquals(OrderStatus.CANCELLED, viewModel.state.value.expandedOrder?.status)
        verifySuspend(exactly(1)) { orderApi.cancelOrder(orderId) }
        verifySuspend(exactly(2)) { orderApi.getOrder(orderId) }
        verify(exactly(1)) { notifier.success("Cancelling order #$orderId.") }
        verify(exactly(1)) { notifier.success("Order #$orderId is cancelled.") }
        verify(not) { notifier.error(any()) }
    }

    @Test
    fun `cancel - warns that an unpaid order turned out to be paid and shows it going on`() = runTest {
        val orderId = 42L
        val alreadyPaid = OrderAlreadyPaidException(orderId)
        everySuspend { orderApi.cancelOrder(orderId) } throws alreadyPaid
        everySuspend { orderApi.getOrder(orderId) } returns order(orderId, OrderStatus.FULFILLED, "2026-11-10T10:01:00Z")
        val viewModel = ordersViewModel()

        viewModel.cancel(orderId)
        runCurrent()

        assertFalse(viewModel.state.value.cancelling)
        verify(exactly(1)) { notifier.warning(alreadyPaid.message) }
        verifySuspend(exactly(1)) { orderApi.getOrder(orderId) }
        verify(not) { errorHandler.handle(any()) }
    }
}
