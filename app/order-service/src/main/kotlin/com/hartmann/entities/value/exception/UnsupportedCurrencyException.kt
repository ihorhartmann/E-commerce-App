package com.hartmann.entities.value.exception

import com.hartmann.entities.exception.InvalidException

class UnsupportedCurrencyException(currencyCode: String, cause: Throwable) : InvalidException(
    ValueErrorCode.UNSUPPORTED_CURRENCY,
    "Unsupported currency",
    mapOf("currency" to currencyCode),
    cause = cause,
)
