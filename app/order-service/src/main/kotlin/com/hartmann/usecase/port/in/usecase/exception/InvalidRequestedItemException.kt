package com.hartmann.usecase.port.`in`.usecase.exception

import com.hartmann.entities.exception.InvalidException

class InvalidRequestedItemException(val productId: Long, val quantity: Int) : InvalidException(
    UseCaseErrorCode.INVALID_REQUESTED_ITEM,
    "Invalid requested item",
    mapOf("productId" to productId, "quantity" to quantity),
)
