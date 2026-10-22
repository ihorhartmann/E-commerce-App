package com.hartmann.usecase.port.`in`.usecase.exception

import com.hartmann.entities.exception.InvalidException

/** [externalMessage] tells the caller which of the two values to fix. */
class InvalidPageException(val page: Int, val size: Int, externalMessage: String) : InvalidException(
    UseCaseErrorCode.INVALID_PAGE,
    "Invalid page",
    mapOf("page" to page, "size" to size),
    externalMessage,
)
