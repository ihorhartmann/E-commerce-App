package com.hartmann.usecase.port.`in`.usecase.exception

import com.hartmann.entities.exception.ErrorCode

/** Codes of the input the use cases reject before doing anything. */
enum class UseCaseErrorCode(override val title: String) : ErrorCode {
    INVALID_REQUESTED_ITEM("Requested item must have a positive product id and quantity"),
    INVALID_PAGE("Invalid page or page size"),
}
