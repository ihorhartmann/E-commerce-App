package com.hartmann.domain.value.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of the rules the value objects enforce. */
public enum ValueErrorCode implements ErrorCode {
    INVALID_MONEY_AMOUNT("Amount must be a non-negative number with at most two decimal places"),
    UNSUPPORTED_CURRENCY("Unsupported currency"),
    CURRENCY_MISMATCH("Amounts are in different currencies");

    private final String title;

    ValueErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
