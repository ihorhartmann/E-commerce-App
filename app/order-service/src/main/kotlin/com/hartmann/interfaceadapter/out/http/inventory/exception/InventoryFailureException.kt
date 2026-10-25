package com.hartmann.interfaceadapter.out.http.inventory.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * The inventory service failed with a 5xx that doesn't ask to be retried. [inventoryCode] is inventory's own
 * error code, if it sent one.
 */
class InventoryFailureException(val orderId: Long, val status: Int, val inventoryCode: String?, cause: Throwable) :
    OrderServiceException(
        InventoryErrorCode.INVENTORY_FAILURE,
        "Inventory service failed",
        attributes = mapOf("orderId" to orderId, "status" to status, "inventoryCode" to inventoryCode),
        cause = cause,
    )
