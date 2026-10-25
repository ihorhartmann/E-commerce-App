package com.hartmann.adapter.out.repository.exception;

import com.hartmann.domain.exception.ErrorCode;

public enum RepositoryErrorCode implements ErrorCode {
    DATABASE_FAILURE("Internal server error");

    private final String title;

    RepositoryErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
