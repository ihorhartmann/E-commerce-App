package com.hartmann.feature.order

import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.error.launchSafely
import com.hartmann.core.notification.Notifier
import com.hartmann.core.model.Page
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderStatus
import com.hartmann.feature.order.model.OrderSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.milliseconds

data class OrdersState(
    val page: Page<OrderSummary> = Page.empty(),
    val loading: Boolean = true,
    val expandedOrderId: Long? = null,
    /** The details of [expandedOrderId], once loaded. */
    val expandedOrder: Order? = null,
    val cancelling: Boolean = false,
)

/**
 * The customer's orders, one of them expanded at a time. The expanded order is polled until it reaches a final
 * status: payment, shipping and refunds move it on asynchronously (the order saga), not as a reply to the UI.
 */
class OrdersViewModel(
    private val scope: CoroutineScope,
    private val orderApi: OrderApi,
    private val notifier: Notifier,
    private val errorHandler: ErrorHandler,
) {
    private val _state = MutableStateFlow(OrdersState())
    val state: StateFlow<OrdersState> = _state
    private var polling: Job? = null
    // orders seen unpaid (or just paid at checkout): the user is told how their payment ends
    private val ordersAwaitingPayment = mutableSetOf<Long>()

    fun load(page: Int) {
        _state.update { it.copy(loading = true) }
        scope.launchSafely(errorHandler) {
            try {
                val orders = orderApi.listOrders(page, PAGE_SIZE)
                _state.update { it.copy(page = orders) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Expands [orderId], or collapses it if it's the one expanded already. */
    fun toggle(orderId: Long) {
        if (_state.value.expandedOrderId == orderId) {
            polling?.cancel()
            _state.update { it.copy(expandedOrderId = null, expandedOrder = null) }
        } else {
            expand(orderId)
        }
    }

    /** [awaitingPayment]: the user just paid the order at checkout, so they're told once the payment is confirmed. */
    fun expand(orderId: Long, awaitingPayment: Boolean = false) {
        if (awaitingPayment) {
            ordersAwaitingPayment += orderId
        }
        _state.update { it.copy(expandedOrderId = orderId, expandedOrder = null) }
        startPolling(orderId, waitFirst = false)
    }

    fun cancel(orderId: Long) {
        _state.update { it.copy(cancelling = true) }
        scope.launchSafely(errorHandler) {
            try {
                val order = orderApi.cancelOrder(orderId)
                notifier.success("Cancelling order #$orderId. A paid amount is refunded once it's done.")
                show(order)
                // the cancellation completes asynchronously: keep watching it
                if (!order.status.isFinal()) {
                    startPolling(orderId, waitFirst = true)
                }
            } finally {
                _state.update { it.copy(cancelling = false) }
            }
        }
    }

    private fun startPolling(orderId: Long, waitFirst: Boolean) {
        polling?.cancel()
        polling = scope.launchSafely(errorHandler) {
            if (waitFirst) {
                delay(POLL_INTERVAL_MILLIS.milliseconds)
            }
            while (true) {
                val order = orderApi.getOrder(orderId)
                notifyAboutPayment(order)
                show(order)
                if (order.status.isFinal()) {
                    break
                }
                delay(POLL_INTERVAL_MILLIS.milliseconds)
            }
        }
    }

    /**
     * The payment-service confirms (or fails) a payment asynchronously, via Kafka: polling is what notices it, and the
     * user is told - also when it's already done by the first time the order is loaded.
     */
    private fun notifyAboutPayment(order: Order) {
        when {
            order.status == OrderStatus.PLACED -> ordersAwaitingPayment += order.id
            order.id !in ordersAwaitingPayment -> return
            order.paymentConfirmedAt != null -> {
                notifier.success("Payment for order #${order.id} is confirmed.")
                ordersAwaitingPayment -= order.id
            }
            order.status == OrderStatus.CANCELLED -> {
                notifier.error("The payment for order #${order.id} didn't go through, so the order is cancelled.")
                ordersAwaitingPayment -= order.id
            }
        }
    }

    /** Puts [order]'s details on screen, if it's still the expanded one, and its status into the list. */
    private fun show(order: Order) {
        _state.update { state ->
            state.copy(
                expandedOrder = if (state.expandedOrderId == order.id) order else state.expandedOrder,
                page = state.page.copy(items = state.page.items.map {
                    if (it.id == order.id) it.copy(status = order.status) else it
                }),
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
