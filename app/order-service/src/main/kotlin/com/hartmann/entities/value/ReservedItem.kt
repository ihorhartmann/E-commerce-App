package com.hartmann.entities.value

import com.hartmann.entities.value.exception.InvalidReservedItemException

/**
 * One line of stock as the inventory reserved it for an order: how much of which product, and the product's name and
 * price at that moment, which the order keeps from then on.
 */
data class ReservedItem(val productId: Long, val name: String, val quantity: Int, val price: Money) {
    init {
        when {
            productId <= 0 -> throw InvalidReservedItemException(productId, "productId must be positive")
            name.isBlank() -> throw InvalidReservedItemException(productId, "name must not be blank")
            quantity <= 0 -> throw InvalidReservedItemException(productId, "quantity must be positive")
        }
    }
}
