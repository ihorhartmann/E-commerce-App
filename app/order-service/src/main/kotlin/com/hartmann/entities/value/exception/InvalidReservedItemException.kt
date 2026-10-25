package com.hartmann.entities.value.exception

import com.hartmann.entities.exception.InvalidException

/**
 * Data the inventory reserved a product with breaks [rule]. Invalid like any other value built from outside data;
 * HttpInventoryClient, which builds reserved items from inventory's response, reports it as an invalid response.
 */
class InvalidReservedItemException(productId: Long, rule: String) : InvalidException(
    ValueErrorCode.INVALID_RESERVED_ITEM,
    "Invalid reserved item",
    mapOf("productId" to productId, "rule" to rule),
)
