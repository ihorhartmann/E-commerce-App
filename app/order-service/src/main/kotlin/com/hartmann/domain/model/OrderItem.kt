package com.hartmann.domain.model

import com.hartmann.domain.value.Money

data class OrderItem(
    val id: Long,
    val orderId: Long,
    val productId: Long,
    val quantity: UInt,
    val price: Money
) {
    init {
        require(productId > 0) { "productId must be positive" }
        require(quantity > 0u) { "quantity must be positive" }
    }
}
