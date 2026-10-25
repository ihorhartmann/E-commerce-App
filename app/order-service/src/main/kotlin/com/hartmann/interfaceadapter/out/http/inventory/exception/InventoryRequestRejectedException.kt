package com.hartmann.interfaceadapter.out.http.inventory.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * The inventory service rejected the request with a 4xx this service doesn't expect: a request this service
 * shouldn't have sent, so an internal failure, not the caller's. [inventoryCode] is inventory's own error code,
 * if it sent one.
 */
class InventoryRequestRejectedException(
    val orderId: Long,
    val status: Int,
    val inventoryCode: String?,
    cause: Throwable,
) : OrderServiceException(
    InventoryErrorCode.INVENTORY_REQUEST_REJECTED,
    "Inventory service rejected the request",
    attributes = mapOf("orderId" to orderId, "status" to status, "inventoryCode" to inventoryCode),
    cause = cause,
)
