package com.hartmann.domain.value;

import com.hartmann.domain.exception.DomainException;

public enum Currency {
    EUR,
    USD,
    GBP;

    public static Currency fromCode(String code) {
        if (code == null) {
            throw new DomainException("Currency code must not be null");
        }
        try {
            return Currency.valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException("Unsupported currency: " + code);
        }
    }
}
