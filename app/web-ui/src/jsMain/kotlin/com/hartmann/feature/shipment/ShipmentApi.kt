package com.hartmann.feature.shipment

import com.hartmann.core.model.Page
import com.hartmann.feature.shipment.model.Shipment
import com.hartmann.feature.shipment.model.ShipmentCancellationReason

/** The calling courier's view of the shipments; every transition acts on their behalf. */
interface ShipmentApi {
    /** Shipments any courier may pick up - without their street. */
    suspend fun listAvailable(page: Int, size: Int): Page<Shipment>

    /** The courier's own shipments still to deliver or bring back. */
    suspend fun listMine(page: Int, size: Int): Page<Shipment>

    suspend fun pickUp(id: Long): Shipment

    suspend fun deliver(id: Long): Shipment

    suspend fun reportLost(id: Long): Shipment

    suspend fun cancel(id: Long, reason: ShipmentCancellationReason): Shipment

    suspend fun confirmReturned(id: Long): Shipment
}
