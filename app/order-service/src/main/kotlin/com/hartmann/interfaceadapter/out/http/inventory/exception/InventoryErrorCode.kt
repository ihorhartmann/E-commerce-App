package com.hartmann.interfaceadapter.out.http.inventory.exception

import com.hartmann.entities.exception.ErrorCode

/** Codes of the failures of calls to the inventory service. */
enum class InventoryErrorCode(override val title: String) : ErrorCode {
    INSUFFICIENT_STOCK("Insufficient stock"),
    INVENTORY_UNAVAILABLE("Inventory service is temporarily unavailable"),
    INVENTORY_FAILURE("Internal server error"),
    INVENTORY_REQUEST_REJECTED("Internal server error"),
    INVALID_INVENTORY_RESPONSE("Internal server error"),
}
