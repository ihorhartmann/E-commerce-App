package com.hartmann.interfaceadapter.out.kafka.dto

import com.hartmann.entities.value.Address

data class CreateShipmentCommand(val orderId: Long, val address: AddressDto)

data class AddressDto(
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String
) {
    companion object {
        fun toAddressDto(address: Address): AddressDto {
            return AddressDto(address.city, address.postalCode, address.line1, address.line2)
        }
    }
}
