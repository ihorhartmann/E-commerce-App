package com.hartmann.interfaceadapter.out.repository.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * What PostgresOrderRepository reports a database error with - its cause is the exception JPA threw. Not part of
 * the repository port: no caller tells it apart from any other internal failure.
 */
class DatabaseFailureException(cause: Throwable) :
    OrderServiceException(RepositoryErrorCode.DATABASE_FAILURE, "Database failure", cause = cause)
