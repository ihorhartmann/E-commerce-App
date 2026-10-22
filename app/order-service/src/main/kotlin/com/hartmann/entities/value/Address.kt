package com.hartmann.entities.value

import com.hartmann.entities.value.exception.InvalidAddressException

data class Address(
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String,
) {
    init {
        requireField("city", city)
        requireField("postalCode", postalCode)
        requireField("line1", line1)
    }

    private fun requireField(field: String, value: String) {
        if (value.isBlank()) {
            throw InvalidAddressException(field)
        }
    }
}
