package com.hartmann.feature.shipment

import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.notification.Notifier
import com.hartmann.core.model.Page
import com.hartmann.feature.shipment.model.Shipment
import com.hartmann.feature.shipment.model.ShipmentCancellationReason
import com.hartmann.feature.shipment.model.ShipmentHolder
import com.hartmann.feature.shipment.model.ShipmentStatus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verify.VerifyMode.Companion.not
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

// the ViewModel's fixed page size - never what a test is about
private const val PAGE_SIZE = 20

@OptIn(ExperimentalCoroutinesApi::class)
class ShipmentsViewModelTest {
    private val shipmentApi = mock<ShipmentApi>()
    private val notifier = mock<Notifier>(MockMode.autoUnit)
    private val errorHandler = mock<ErrorHandler>(MockMode.autoUnit)

    private fun TestScope.viewModel() = ShipmentsViewModel(backgroundScope, shipmentApi, notifier, errorHandler)

    private fun shipment(id: Long, status: ShipmentStatus) =
        Shipment(id, 42, "Kyiv", "01001", "", "", status, ShipmentHolder.INVENTORY, null)

    @Test
    fun `selectTab - switches to the tab and loads its first page`() = runTest {
        val firstPage = 0
        val expected = Page(listOf(shipment(17, ShipmentStatus.IN_DELIVERY)), firstPage, 1)
        everySuspend { shipmentApi.listMine(firstPage, PAGE_SIZE) } returns expected
        val viewModel = viewModel()

        viewModel.selectTab(ShipmentsTab.MINE)
        runCurrent()

        assertEquals(ShipmentsTab.MINE, viewModel.state.value.tab)
        assertEquals(expected, viewModel.state.value.page)
        verifySuspend(exactly(1)) { shipmentApi.listMine(firstPage, PAGE_SIZE) }
    }

    @Test
    fun `load - lists the shipments available for pickup on the Available tab`() = runTest {
        val pageNumber = 1
        val expected = Page(listOf(shipment(17, ShipmentStatus.CREATED)), pageNumber, 2)
        everySuspend { shipmentApi.listAvailable(pageNumber, PAGE_SIZE) } returns expected
        val viewModel = viewModel()

        viewModel.load(pageNumber)
        runCurrent()

        assertEquals(expected, viewModel.state.value.page)
        assertFalse(viewModel.state.value.loading)
        verifySuspend(exactly(1)) { shipmentApi.listAvailable(pageNumber, PAGE_SIZE) }
        verifySuspend(not) { shipmentApi.listMine(any(), any()) }
    }

    @Test
    fun `load - lists the courier's own shipments on the Mine tab`() = runTest {
        val pageNumber = 1
        val expected = Page(listOf(shipment(17, ShipmentStatus.IN_DELIVERY)), pageNumber, 2)
        everySuspend { shipmentApi.listMine(any(), PAGE_SIZE) } returns expected
        val viewModel = viewModel()
        viewModel.selectTab(ShipmentsTab.MINE)
        runCurrent()

        viewModel.load(pageNumber)
        runCurrent()

        assertEquals(expected, viewModel.state.value.page)
        verifySuspend(exactly(1)) { shipmentApi.listMine(pageNumber, PAGE_SIZE) }
        verifySuspend(not) { shipmentApi.listAvailable(any(), any()) }
    }

    @Test
    fun `load - hands a failure to the error handler and stops loading`() = runTest {
        val error = IllegalStateException("down")
        everySuspend { shipmentApi.listAvailable(any(), any()) } throws error
        val viewModel = viewModel()

        viewModel.load(0)
        runCurrent()

        assertFalse(viewModel.state.value.loading)
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `pickUp - picks the shipment up and reloads the page`() = runTest {
        val shipmentId = 17L
        val firstPage = 0
        everySuspend { shipmentApi.pickUp(shipmentId) } returns shipment(shipmentId, ShipmentStatus.IN_DELIVERY)
        everySuspend { shipmentApi.listAvailable(firstPage, PAGE_SIZE) } returns Page.empty()
        val viewModel = viewModel()

        viewModel.pickUp(shipmentId)
        runCurrent()

        assertNull(viewModel.state.value.busyShipmentId)
        verifySuspend(exactly(1)) { shipmentApi.pickUp(shipmentId) }
        verifySuspend(exactly(1)) { shipmentApi.listAvailable(firstPage, PAGE_SIZE) }
        verify(exactly(1)) { notifier.success("Shipment #$shipmentId picked up - it's in Mine now.") }
    }

    @Test
    fun `pickUp - hands a failure to the error handler without reloading`() = runTest {
        val error = IllegalStateException("already picked up")
        everySuspend { shipmentApi.pickUp(any()) } throws error
        val viewModel = viewModel()

        viewModel.pickUp(17)
        runCurrent()

        assertNull(viewModel.state.value.busyShipmentId)
        verify(exactly(1)) { errorHandler.handle(error) }
        verify(not) { notifier.success(any()) }
        verifySuspend(not) { shipmentApi.listAvailable(any(), any()) }
    }

    @Test
    fun `deliver - delivers the shipment and reloads the page`() = runTest {
        val shipmentId = 17L
        val firstPage = 0
        everySuspend { shipmentApi.deliver(shipmentId) } returns shipment(shipmentId, ShipmentStatus.DELIVERED)
        everySuspend { shipmentApi.listAvailable(firstPage, PAGE_SIZE) } returns Page.empty()
        val viewModel = viewModel()

        viewModel.deliver(shipmentId)
        runCurrent()

        verifySuspend(exactly(1)) { shipmentApi.deliver(shipmentId) }
        verifySuspend(exactly(1)) { shipmentApi.listAvailable(firstPage, PAGE_SIZE) }
        verify(exactly(1)) { notifier.success("Shipment #$shipmentId delivered.") }
    }

    @Test
    fun `reportLost - reports the shipment as lost and reloads the page`() = runTest {
        val shipmentId = 17L
        val firstPage = 0
        everySuspend { shipmentApi.reportLost(shipmentId) } returns shipment(shipmentId, ShipmentStatus.LOST)
        everySuspend { shipmentApi.listAvailable(firstPage, PAGE_SIZE) } returns Page.empty()
        val viewModel = viewModel()

        viewModel.reportLost(shipmentId)
        runCurrent()

        verifySuspend(exactly(1)) { shipmentApi.reportLost(shipmentId) }
        verifySuspend(exactly(1)) { shipmentApi.listAvailable(firstPage, PAGE_SIZE) }
        verify(exactly(1)) { notifier.success("Shipment #$shipmentId reported as lost.") }
    }

    @Test
    fun `confirmReturned - records the goods as back in the warehouse and reloads the page`() = runTest {
        val shipmentId = 17L
        val firstPage = 0
        everySuspend { shipmentApi.confirmReturned(shipmentId) } returns shipment(shipmentId, ShipmentStatus.CANCELLED)
        everySuspend { shipmentApi.listAvailable(firstPage, PAGE_SIZE) } returns Page.empty()
        val viewModel = viewModel()

        viewModel.confirmReturned(shipmentId)
        runCurrent()

        verifySuspend(exactly(1)) { shipmentApi.confirmReturned(shipmentId) }
        verifySuspend(exactly(1)) { shipmentApi.listAvailable(firstPage, PAGE_SIZE) }
        verify(exactly(1)) { notifier.success("Shipment #$shipmentId is back in the warehouse.") }
    }

    @Test
    fun `openCancelDialog - asks for the reason of the shipment's cancellation`() = runTest {
        val shipmentId = 17L
        val viewModel = viewModel()

        viewModel.openCancelDialog(shipmentId)

        assertEquals(shipmentId, viewModel.state.value.cancelDialogShipmentId)
    }

    @Test
    fun `closeCancelDialog - closes the dialog`() = runTest {
        val viewModel = viewModel()
        viewModel.openCancelDialog(17)

        viewModel.closeCancelDialog()

        assertNull(viewModel.state.value.cancelDialogShipmentId)
    }

    @Test
    fun `cancel - cancels the dialog's shipment for the given reason and reloads the page`() = runTest {
        val shipmentId = 17L
        val firstPage = 0
        val reason = ShipmentCancellationReason.RECIPIENT_REFUSED
        everySuspend { shipmentApi.cancel(shipmentId, reason) } returns shipment(shipmentId, ShipmentStatus.CANCELLED)
        everySuspend { shipmentApi.listAvailable(firstPage, PAGE_SIZE) } returns Page.empty()
        val viewModel = viewModel()
        viewModel.openCancelDialog(shipmentId)

        viewModel.cancel(reason)
        runCurrent()

        assertNull(viewModel.state.value.cancelDialogShipmentId)
        verifySuspend(exactly(1)) { shipmentApi.cancel(shipmentId, reason) }
        verifySuspend(exactly(1)) { shipmentApi.listAvailable(firstPage, PAGE_SIZE) }
        verify(exactly(1)) { notifier.success("Shipment #$shipmentId cancelled - bring the goods back to the warehouse.") }
    }

    @Test
    fun `cancel - does nothing without an open dialog`() = runTest {
        val viewModel = viewModel()

        viewModel.cancel(ShipmentCancellationReason.RECIPIENT_UNAVAILABLE)
        runCurrent()

        verifySuspend(not) { shipmentApi.cancel(any(), any()) }
    }
}
