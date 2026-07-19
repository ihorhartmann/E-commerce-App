package com.hartmann.domain.value

data class Address(
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String,
) {
    init {
        require(city.isNotBlank()) { "city is required" }
        require(postalCode.isNotBlank()) { "postalCode is required" }
        require(line1.isNotBlank()) { "line1 is required" }
    }
}
