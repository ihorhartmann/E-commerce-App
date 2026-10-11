package com.hartmann.usecase.port.out.internal

import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RequestedItem

interface InventoryClient {
    /**
     * Reserves the stock of every requested item for the order, or none of it, and returns what each product costs,
     * by product id - the only source of an order's prices. Throws an InsufficientStockException if any of them can't
     * be covered.
     */
    fun reserve(orderId: Long, requestedItems: List<RequestedItem>): Map<Long, Money>
}
