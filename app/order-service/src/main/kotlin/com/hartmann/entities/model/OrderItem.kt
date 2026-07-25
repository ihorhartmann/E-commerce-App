package com.hartmann.entities.model

import com.hartmann.entities.value.Money

data class OrderItem(
    val id: Long,
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
    val price: Money,
) {
    init {
        require(id >= 0) { "id must not be negative" }
        require(orderId >= 0) { "orderId must not be negative" }
        require(productId > 0) { "productId must be positive" }
        require(quantity > 0) { "quantity must be positive" }
    }
}
