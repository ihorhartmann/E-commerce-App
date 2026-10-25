package com.hartmann.domain.value.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Collections;

/** cause is the IllegalArgumentException of an unknown code; a missing code has none. */
public class UnsupportedCurrencyException extends InvalidException {
    public UnsupportedCurrencyException(String currencyCode, Throwable cause) {
        // singletonMap rather than Map.of, which rejects the null of a missing code
        super(ValueErrorCode.UNSUPPORTED_CURRENCY, "Unsupported currency",
                Collections.singletonMap("currency", currencyCode), cause);
    }
}
