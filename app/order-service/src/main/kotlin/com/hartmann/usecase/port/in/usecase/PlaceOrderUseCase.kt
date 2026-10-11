package com.hartmann.usecase.port.`in`.usecase

import com.hartmann.entities.value.Address
import com.hartmann.entities.value.RequestedItem
import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import java.util.UUID

interface PlaceOrderUseCase {
    /**
     * Reserves the stock, stores the order priced by the inventory, and starts its payment. If the stock can't be
     * reserved, no order is created at all.
     */
    fun execute(customerId: UUID, requestedItems: List<RequestedItem>, address: Address): CheckoutResult
}
