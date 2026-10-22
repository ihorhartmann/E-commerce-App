package com.hartmann.interfaceadapter.out.http.inventory.exception

import com.hartmann.entities.exception.UnavailableException

/** The inventory service is unreachable, its circuit breaker is open, or it asked to be retried later. */
class InventoryUnavailableException(val orderId: Long, cause: Throwable) : UnavailableException(
    InventoryErrorCode.INVENTORY_UNAVAILABLE,
    "Inventory service unavailable",
    mapOf("orderId" to orderId),
    cause = cause,
)
