package com.hartmann.usecase.port.out.repository.exception

import com.hartmann.entities.exception.ErrorCode

enum class RepositoryErrorCode(override val title: String) : ErrorCode {
    ORDER_NOT_FOUND("Order not found"),
    CONCURRENT_UPDATE("The order was changed concurrently, try again"),
}
