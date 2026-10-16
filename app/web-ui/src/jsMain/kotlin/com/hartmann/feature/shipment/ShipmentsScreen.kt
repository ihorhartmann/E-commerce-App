package com.hartmann.feature.shipment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hartmann.core.ui.AppButton
import com.hartmann.core.ui.Badge
import com.hartmann.core.ui.BadgeTone
import com.hartmann.core.ui.ButtonStyle
import com.hartmann.core.ui.Dialog
import com.hartmann.core.ui.EmptyState
import com.hartmann.core.ui.Pagination
import com.hartmann.core.ui.Spinner
import com.hartmann.feature.shipment.model.Shipment
import com.hartmann.feature.shipment.model.ShipmentCancellationReason
import com.hartmann.feature.shipment.model.ShipmentStatus
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

@Composable
fun ShipmentsScreen(viewModel: ShipmentsViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load(0) }

    Div({ classes("screen-header") }) { H1 { Text("Shipments") } }
    Div({
        classes("glass", "tabs")
        attr("role", "tablist")
    }) {
        ShipmentsTab.entries.forEach { tab ->
            Button({
                type(ButtonType.Button)
                classes("tab")
                if (tab == state.tab) {
                    classes("tab-active")
                }
                attr("role", "tab")
                attr("aria-selected", (tab == state.tab).toString())
                onClick { viewModel.selectTab(tab) }
            }) { Text(tab.label) }
        }
    }
    when {
        state.loading && state.page.items.isEmpty() -> Spinner()
        state.page.items.isEmpty() -> EmptyState(
            if (state.tab == ShipmentsTab.AVAILABLE) "No shipments are waiting for a courier." else "You have nothing to deliver right now."
        )
        else -> Div({ classes("grid") }) {
            state.page.items.forEach { shipment ->
                ShipmentCard(shipment, busy = state.busyShipmentId == shipment.id, viewModel)
            }
        }
    }
    Pagination(state.page, viewModel::load)
    if (state.cancelDialogShipmentId != null) {
        CancelDialog(viewModel)
    }
}

@Composable
private fun ShipmentCard(shipment: Shipment, busy: Boolean, viewModel: ShipmentsViewModel) {
    Div({ classes("glass", "card") }) {
        Div({ classes("row") }) {
            H3({ classes("card-title") }) { Text("Shipment #${shipment.id}") }
            ShipmentStatusBadge(shipment.status)
        }
        P({ classes("muted") }) { Text("Order #${shipment.orderId}") }
        P {
            Text(listOf(shipment.line1, shipment.line2, "${shipment.postalCode} ${shipment.city}").filter { it.isNotBlank() }.joinToString(", "))
        }
        shipment.cancellationReason?.let { P({ classes("muted") }) { Text("Reason: ${it.label}") } }
        if (shipment.awaitsReturn()) {
            P({ classes("muted") }) { Text("You still have the goods: bring them back to the warehouse.") }
        }
        Div({ classes("actions") }) {
            when {
                shipment.canBePickedUp() ->
                    AppButton("Pick up", { viewModel.pickUp(shipment.id) }, ButtonStyle.PRIMARY, !busy)
                shipment.isInDelivery() -> {
                    AppButton("Delivered", { viewModel.deliver(shipment.id) }, ButtonStyle.PRIMARY, !busy)
                    AppButton("Couldn't deliver", { viewModel.openCancelDialog(shipment.id) }, enabled = !busy)
                    AppButton("Lost", { viewModel.reportLost(shipment.id) }, ButtonStyle.DANGER, !busy)
                }
                shipment.awaitsReturn() ->
                    AppButton("Returned to warehouse", { viewModel.confirmReturned(shipment.id) }, ButtonStyle.PRIMARY, !busy)
            }
        }
    }
}

@Composable
private fun CancelDialog(viewModel: ShipmentsViewModel) {
    Dialog("Why couldn't you deliver it?", viewModel::closeCancelDialog) {
        Div({ classes("actions", "actions-stacked") }) {
            ShipmentCancellationReason.courierReasons().forEach { reason ->
                AppButton(reason.label, { viewModel.cancel(reason) })
            }
            AppButton("Back", viewModel::closeCancelDialog)
        }
    }
}

@Composable
private fun ShipmentStatusBadge(status: ShipmentStatus) {
    val tone = when (status) {
        ShipmentStatus.CREATED -> BadgeTone.NEUTRAL
        ShipmentStatus.IN_DELIVERY -> BadgeTone.INFO
        ShipmentStatus.DELIVERED -> BadgeTone.SUCCESS
        ShipmentStatus.CANCELLED -> BadgeTone.WARNING
        ShipmentStatus.LOST -> BadgeTone.DANGER
    }
    Badge(status.label, tone)
}
