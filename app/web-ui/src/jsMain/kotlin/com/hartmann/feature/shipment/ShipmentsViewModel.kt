package com.hartmann.feature.shipment

import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.error.launchSafely
import com.hartmann.core.notification.Notifier
import com.hartmann.core.model.Page
import com.hartmann.feature.shipment.model.Shipment
import com.hartmann.feature.shipment.model.ShipmentCancellationReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class ShipmentsTab(val label: String) {
    AVAILABLE("Available"),
    MINE("Mine"),
}

data class ShipmentsState(
    val tab: ShipmentsTab = ShipmentsTab.AVAILABLE,
    val page: Page<Shipment> = Page.empty(),
    val loading: Boolean = true,
    /** The shipment an action is running for, so its buttons can't be pressed twice. */
    val busyShipmentId: Long? = null,
    /** The shipment whose cancel dialog (asking for the reason) is open. */
    val cancelDialogShipmentId: Long? = null,
)

class ShipmentsViewModel(
    private val scope: CoroutineScope,
    private val shipmentApi: ShipmentApi,
    private val notifier: Notifier,
    private val errorHandler: ErrorHandler,
) {
    private val _state = MutableStateFlow(ShipmentsState())
    val state: StateFlow<ShipmentsState> = _state

    fun selectTab(tab: ShipmentsTab) {
        _state.update { it.copy(tab = tab, page = Page.empty()) }
        load(0)
    }

    fun load(page: Int) {
        val tab = _state.value.tab
        _state.update { it.copy(loading = true) }
        scope.launchSafely(errorHandler) {
            try {
                val shipments = when (tab) {
                    ShipmentsTab.AVAILABLE -> shipmentApi.listAvailable(page, PAGE_SIZE)
                    ShipmentsTab.MINE -> shipmentApi.listMine(page, PAGE_SIZE)
                }
                _state.update { it.copy(page = shipments) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun pickUp(id: Long) = transition(id, "Shipment #$id picked up - it's in Mine now.") { shipmentApi.pickUp(id) }

    fun deliver(id: Long) = transition(id, "Shipment #$id delivered.") { shipmentApi.deliver(id) }

    fun reportLost(id: Long) = transition(id, "Shipment #$id reported as lost.") { shipmentApi.reportLost(id) }

    fun confirmReturned(id: Long) =
        transition(id, "Shipment #$id is back in the warehouse.") { shipmentApi.confirmReturned(id) }

    fun openCancelDialog(id: Long) {
        _state.update { it.copy(cancelDialogShipmentId = id) }
    }

    fun closeCancelDialog() {
        _state.update { it.copy(cancelDialogShipmentId = null) }
    }

    fun cancel(reason: ShipmentCancellationReason) {
        val id = _state.value.cancelDialogShipmentId ?: return
        closeCancelDialog()
        transition(id, "Shipment #$id cancelled - bring the goods back to the warehouse.") { shipmentApi.cancel(id, reason) }
    }

    /** Runs a transition, then reloads the page: the shipment may have moved from one tab to the other. */
    private fun transition(id: Long, successMessage: String, action: suspend () -> Shipment) {
        _state.update { it.copy(busyShipmentId = id) }
        scope.launchSafely(errorHandler) {
            try {
                action()
                notifier.success(successMessage)
                load(_state.value.page.number)
            } finally {
                _state.update { it.copy(busyShipmentId = null) }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
