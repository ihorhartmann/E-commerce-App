package com.hartmann.usecase.port.`in`.usecase.dto

/** What a customer asks for, before it is reserved and priced: the price is never theirs to name. */
data class RequestedItem(val productId: Long, val quantity: Int) {
    init {
        require(productId > 0) { "productId must be positive" }
        require(quantity > 0) { "quantity must be positive" }
    }
}
