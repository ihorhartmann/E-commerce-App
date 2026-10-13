package com.hartmann.entities.value

/**
 * One line of stock as the inventory reserved it for an order: how much of which product, and the product's name and
 * price at that moment, which the order keeps from then on.
 */
data class ReservedItem(val productId: Long, val name: String, val quantity: Int, val price: Money) {
    init {
        require(productId > 0) { "productId must be positive" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(quantity > 0) { "quantity must be positive" }
    }
}
