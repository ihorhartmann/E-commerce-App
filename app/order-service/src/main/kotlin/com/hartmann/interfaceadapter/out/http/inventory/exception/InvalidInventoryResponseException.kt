package com.hartmann.interfaceadapter.out.http.inventory.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * The inventory service answered with reserved items this service can't accept, e.g. in an unsupported currency.
 * An internal failure: the caller's request was fine, so the domain exception that rejected the items - which
 * would read as the caller's fault - is only the cause.
 */
class InvalidInventoryResponseException(val orderId: Long, cause: Throwable) : OrderServiceException(
    InventoryErrorCode.INVALID_INVENTORY_RESPONSE,
    "Invalid inventory service response",
    attributes = mapOf("orderId" to orderId),
    cause = cause,
)
