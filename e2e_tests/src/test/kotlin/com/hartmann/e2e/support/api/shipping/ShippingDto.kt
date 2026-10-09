package com.hartmann.e2e.support.api.shipping

data class ShippingAddressDto(val city: String, val postalCode: String, val line1: String?, val line2: String?)

data class ShipmentResponse(
    val id: Long,
    val orderId: Long,
    val address: ShippingAddressDto,
    val status: String,
    val holder: String,
    val createdAt: String,
    val updatedAt: String?,
)

data class ShipmentListResponse(
    val items: List<ShipmentResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)
