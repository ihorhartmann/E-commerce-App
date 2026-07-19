package com.hartmann.infrastructure.kafka.dto

import com.hartmann.domain.value.Address

data class CreateShipmentMessage(val orderId: Long, val address: AddressDto)

data class AddressDto(
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String
) {
    companion object {
        fun fromDomain(address: Address): AddressDto {
            return AddressDto(address.city, address.postalCode, address.line1, address.line2)
        }
    }
}
