package com.hartmann.entities.value.exception

import com.hartmann.entities.exception.ErrorCode

/** Codes of the rules the value objects enforce. */
enum class ValueErrorCode(override val title: String) : ErrorCode {
    INVALID_MONEY_AMOUNT("Amount must be a non-negative number with at most two decimal places"),
    UNSUPPORTED_CURRENCY("Unsupported currency"),
    INVALID_ADDRESS("Address is incomplete"),
    SHIPMENT_TRANSITION_NOT_ALLOWED("Shipment can't make this transition in its current state"),
    REFUND_TRANSITION_NOT_ALLOWED("Refund can't make this transition in its current state"),
}
