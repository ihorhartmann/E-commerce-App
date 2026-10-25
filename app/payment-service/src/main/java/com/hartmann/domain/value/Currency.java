package com.hartmann.domain.value;

import com.hartmann.domain.value.exception.UnsupportedCurrencyException;

public enum Currency {
    EUR,
    USD,
    GBP;

    public static Currency fromCode(String code) {
        // e.g. a request or a message without a currency
        if (code == null) {
            throw new UnsupportedCurrencyException(null, null);
        }
        try {
            return Currency.valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedCurrencyException(code, e);
        }
    }
}
