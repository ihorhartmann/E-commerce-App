package com.hartmann.usecase.port.out.internal

import com.hartmann.entities.value.ReservedItem
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem

interface InventoryClient {
    /**
     * Reserves the stock of every requested item for the order, or none of it, and returns the lines as they were
     * reserved - the only source of an order's items, with their product names and prices. Throws an
     * InsufficientStockException if any of them can't be covered.
     */
    fun reserve(orderId: Long, requestedItems: List<RequestedItem>): List<ReservedItem>
}
