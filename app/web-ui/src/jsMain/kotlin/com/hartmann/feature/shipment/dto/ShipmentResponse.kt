package com.hartmann.feature.shipment.dto

import com.hartmann.core.network.exception.UnexpectedResponseException
import com.hartmann.core.model.Page
import com.hartmann.feature.shipment.model.Shipment
import com.hartmann.feature.shipment.model.ShipmentCancellationReason
import com.hartmann.feature.shipment.model.ShipmentHolder
import com.hartmann.feature.shipment.model.ShipmentStatus
import kotlinx.serialization.Serializable

@Serializable
data class ShipmentResponse(
    val id: Long,
    val orderId: Long,
    val address: AddressResponse,
    val status: String,
    val holder: String,
    val cancellationReason: String?,
)

@Serializable
data class AddressResponse(val city: String, val postalCode: String, val line1: String = "", val line2: String = "")

@Serializable
data class ListShipmentsResponse(
    val items: List<ShipmentResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun fromShipmentResponse(response: ShipmentResponse): Shipment = Shipment(
    id = response.id,
    orderId = response.orderId,
    city = response.address.city,
    postalCode = response.address.postalCode,
    line1 = response.address.line1,
    line2 = response.address.line2,
    status = ShipmentStatus.fromApiName(response.status) ?: unexpected("status", response.status),
    holder = ShipmentHolder.fromApiName(response.holder) ?: unexpected("holder", response.holder),
    cancellationReason = response.cancellationReason?.let {
        ShipmentCancellationReason.fromApiName(it) ?: unexpected("cancellation reason", it)
    },
)

fun fromListShipmentsResponse(response: ListShipmentsResponse): Page<Shipment> =
    Page(response.items.map { fromShipmentResponse(it) }, response.page, response.totalPages)

private fun unexpected(field: String, value: String): Nothing =
    throw UnexpectedResponseException("Unexpected shipment $field in a response: $value")
