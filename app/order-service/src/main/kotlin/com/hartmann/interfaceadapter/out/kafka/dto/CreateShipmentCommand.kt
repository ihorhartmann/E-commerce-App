package com.hartmann.interfaceadapter.out.kafka.dto

import com.hartmann.entities.value.Address
import java.util.UUID

data class AddressDto(
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String,
)

private fun toAddressDto(address: Address): AddressDto =
    AddressDto(address.city, address.postalCode, address.line1, address.line2)

data class CreateShipmentCommand(val orderId: Long, val customerId: UUID, val address: AddressDto)

fun toCreateShipmentCommand(orderId: Long, customerId: UUID, address: Address): CreateShipmentCommand =
    CreateShipmentCommand(orderId, customerId, toAddressDto(address))
