package com.hartmann.adapter.out.repository.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.Map;

/**
 * What the Postgres repositories report a database error with - its cause is the exception jOOQ threw. Not part
 * of the repository ports: no caller tells it apart from any other internal failure.
 */
public class DatabaseFailureException extends PaymentServiceException {
    public DatabaseFailureException(Throwable cause) {
        super(RepositoryErrorCode.DATABASE_FAILURE, "Database failure", null, Map.of(), cause);
    }
}
