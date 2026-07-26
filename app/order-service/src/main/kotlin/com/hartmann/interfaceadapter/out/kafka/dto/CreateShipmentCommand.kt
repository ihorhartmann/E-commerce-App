package com.hartmann.interfaceadapter.out.kafka.dto

import com.hartmann.entities.value.Address

data class AddressDto(
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String,
)

private fun toAddressDto(address: Address): AddressDto =
    AddressDto(address.city, address.postalCode, address.line1, address.line2)

data class CreateShipmentCommand(val orderId: Long, val address: AddressDto)

fun toCreateShipmentCommand(orderId: Long, address: Address): CreateShipmentCommand =
    CreateShipmentCommand(orderId, toAddressDto(address))
