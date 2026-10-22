package com.hartmann.usecase.port.`in`.usecase.dto

import com.hartmann.usecase.port.`in`.usecase.exception.InvalidRequestedItemException

/** What a customer asks for, before it is reserved and priced: the price is never theirs to name. */
data class RequestedItem(val productId: Long, val quantity: Int) {
    init {
        if (productId <= 0 || quantity <= 0) {
            throw InvalidRequestedItemException(productId, quantity)
        }
    }
}
