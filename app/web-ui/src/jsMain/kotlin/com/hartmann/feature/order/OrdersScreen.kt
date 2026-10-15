package com.hartmann.feature.order

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hartmann.core.ui.AppButton
import com.hartmann.core.ui.Badge
import com.hartmann.core.ui.BadgeTone
import com.hartmann.core.ui.ButtonStyle
import com.hartmann.core.ui.EmptyState
import com.hartmann.core.ui.Pagination
import com.hartmann.core.ui.Spinner
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderStatus
import com.hartmann.feature.order.model.OrderSummary
import com.hartmann.feature.order.model.cancellationReasonLabel
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import kotlin.js.Date

/**
 * [focusOrderId]: an order to open right away - the one just checked out; [focusOrderPaid]: whether it was paid there,
 * so the user is told once the payment is confirmed.
 */
@Composable
fun OrdersScreen(viewModel: OrdersViewModel, focusOrderId: Long?, focusOrderPaid: Boolean) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.load(0)
        focusOrderId?.let { viewModel.expand(it, awaitingPayment = focusOrderPaid) }
    }

    Div({ classes("screen-header") }) { H1 { Text("Your orders") } }
    when {
        state.loading && state.page.items.isEmpty() -> Spinner()
        state.page.items.isEmpty() -> EmptyState("You haven't ordered anything yet.")
        else -> Div({ classes("list") }) {
            state.page.items.forEach { summary ->
                val expanded = state.expandedOrderId == summary.id
                Div({ classes("glass", "order") }) {
                    OrderHeader(summary, expanded) { viewModel.toggle(summary.id) }
                    if (expanded) {
                        val order = state.expandedOrder
                        if (order == null) Spinner() else OrderDetails(order, state.cancelling) { viewModel.cancel(order.id) }
                    }
                }
            }
        }
    }
    Pagination(state.page, viewModel::load)
}

@Composable
private fun OrderHeader(summary: OrderSummary, expanded: Boolean, onToggle: () -> Unit) {
    Button({
        type(ButtonType.Button)
        classes("order-header")
        attr("aria-expanded", expanded.toString())
        onClick { onToggle() }
    }) {
        Span({ classes("order-chevron") }) { Text(if (expanded) "▾" else "▸") }
        Span({ classes("order-id") }) { Text("Order #${summary.id}") }
        Span({ classes("muted") }) { Text(formatTimestamp(summary.createdAt)) }
        Span({ classes("muted") }) { Text(if (summary.itemCount == 1) "1 item" else "${summary.itemCount} items") }
        Span({ classes("order-total") }) { Text(summary.total.format()) }
        OrderStatusBadge(summary.status)
    }
}

@Composable
private fun OrderDetails(order: Order, cancelling: Boolean, onCancel: () -> Unit) {
    Div({ classes("order-details") }) {
        Div {
            H3 { Text("Items") }
            Ul({ classes("plain-list") }) {
                order.items.forEach { item ->
                    Li { Text("${item.productName} × ${item.quantity} — ${(item.price * item.quantity).format()}") }
                }
            }
        }
        Div {
            H3 { Text("Delivery address") }
            P {
                Text("${order.address.line1}${if (order.address.line2.isNotBlank()) ", ${order.address.line2}" else ""}")
            }
            P { Text("${order.address.postalCode} ${order.address.city}") }
        }
        Div {
            H3 { Text("Progress") }
            Ul({ classes("plain-list") }) {
                Li { Text("Placed: ${formatTimestamp(order.createdAt)}") }
                order.paymentConfirmedAt?.let { Li { Text("Paid: ${formatTimestamp(it)}") } }
                order.shipment?.pickedUpAt?.let { Li { Text("Picked up by the courier: ${formatTimestamp(it)}") } }
                order.shipment?.deliveredAt?.let { Li { Text("Delivered: ${formatTimestamp(it)}") } }
                order.shipment?.returnedAt?.let { Li { Text("Returned to the warehouse: ${formatTimestamp(it)}") } }
                order.cancellationReason?.let { Li { Text("Cancellation: ${cancellationReasonLabel(it)}") } }
            }
        }
        order.refund?.let { refund ->
            Div {
                H3 { Text("Refund") }
                P {
                    Text(
                        when {
                            refund.confirmedAt != null ->
                                "${refund.amount?.format() ?: "The amount"} refunded on ${formatTimestamp(refund.confirmedAt)}."
                            refund.failedAt != null -> "The refund failed. Contact support."
                            else -> "Requested on ${formatTimestamp(refund.requestedAt)}, on its way."
                        }
                    )
                }
            }
        }
    }
    if (order.status.isCancellable()) {
        Div({ classes("actions") }) {
            AppButton(if (cancelling) "Cancelling…" else "Cancel order", onCancel, ButtonStyle.DANGER, !cancelling)
        }
    }
}

@Composable
private fun OrderStatusBadge(status: OrderStatus) {
    val tone = when (status) {
        OrderStatus.PLACED, OrderStatus.CANCEL_REQUESTED -> BadgeTone.WARNING
        OrderStatus.PROCESSING -> BadgeTone.INFO
        OrderStatus.CANCELLED -> BadgeTone.DANGER
        OrderStatus.FULFILLED -> BadgeTone.SUCCESS
    }
    Badge(status.label, tone)
}

// Java's Instant writes up to 9 fraction digits; JS dates only parse reliably up to 3 (milliseconds)
private val EXTRA_FRACTION_DIGITS = Regex("""(\.\d{3})\d+""")

/** An ISO-8601 timestamp from the API, in the browser's time zone, e.g. `14 Oct 2026, 12:04`. */
private fun formatTimestamp(iso: String): String =
    Date(iso.replace(EXTRA_FRACTION_DIGITS, "$1")).toLocaleString("en-GB", dateLocaleOptions {
        day = "numeric"
        month = "short"
        year = "numeric"
        hour = "2-digit"
        minute = "2-digit"
    })
