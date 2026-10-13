package com.hartmann.interfaceadapter.`in`.http.dto

import com.hartmann.entities.value.Address
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

data class PlaceOrderRequest(
    @field:NotEmpty @field:Valid val items: List<OrderItemRequest>,
    @field:NotNull @field:Valid val address: AddressRequest,
)

/** customerId comes from the caller's access token, never from the request body - a client can't order for someone else. */
data class OrderItemRequest(
    @field:Positive val productId: Long,
    @field:Positive val quantity: Int,
)

fun fromOrderItemRequests(requests: List<OrderItemRequest>): List<RequestedItem> =
    requests.map { RequestedItem(it.productId, it.quantity) }

data class AddressRequest(
    @field:NotBlank val city: String,
    @field:NotBlank val postalCode: String,
    @field:NotBlank val line1: String,
    val line2: String = "",
)

fun fromAddressRequest(request: AddressRequest): Address =
    Address(request.city, request.postalCode, request.line1, request.line2)
