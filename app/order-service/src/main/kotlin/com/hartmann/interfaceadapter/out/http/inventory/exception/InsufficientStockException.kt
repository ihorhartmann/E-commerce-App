package com.hartmann.interfaceadapter.out.http.inventory.exception

import com.hartmann.entities.exception.ConflictException

class InsufficientStockException(val orderId: Long, cause: Throwable) : ConflictException(
    InventoryErrorCode.INSUFFICIENT_STOCK,
    "Insufficient stock",
    mapOf("orderId" to orderId),
    cause = cause,
)
