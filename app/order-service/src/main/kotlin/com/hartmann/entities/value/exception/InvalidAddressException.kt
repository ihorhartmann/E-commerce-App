package com.hartmann.entities.value.exception

import com.hartmann.entities.exception.InvalidException

/** [field] is the address field that is missing - told to the caller too, so they know what to fill in. */
class InvalidAddressException(val field: String) : InvalidException(
    ValueErrorCode.INVALID_ADDRESS,
    "Invalid address",
    mapOf("field" to field),
    externalMessage = "$field is required",
)
