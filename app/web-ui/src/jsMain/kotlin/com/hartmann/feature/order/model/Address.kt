package com.hartmann.feature.order.model

data class Address(val city: String, val postalCode: String, val line1: String, val line2: String = "") {
    /** The fields the order-service requires but are still blank; [line2] is optional. */
    fun missingFields(): Set<AddressField> = buildSet {
        if (city.isBlank()) add(AddressField.CITY)
        if (postalCode.isBlank()) add(AddressField.POSTAL_CODE)
        if (line1.isBlank()) add(AddressField.LINE1)
    }

    fun trimmed(): Address = Address(city.trim(), postalCode.trim(), line1.trim(), line2.trim())
}

/** The required fields of an [Address], each with what the user is asked when it's missing. */
enum class AddressField(val missingMessage: String) {
    CITY("Enter your city."),
    POSTAL_CODE("Enter your postal code."),
    LINE1("Enter your street and house number."),
}
