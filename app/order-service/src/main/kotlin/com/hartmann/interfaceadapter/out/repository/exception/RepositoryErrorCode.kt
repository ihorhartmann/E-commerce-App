package com.hartmann.interfaceadapter.out.repository.exception

import com.hartmann.entities.exception.ErrorCode

enum class RepositoryErrorCode(override val title: String) : ErrorCode {
    DATABASE_FAILURE("Internal server error"),
}
