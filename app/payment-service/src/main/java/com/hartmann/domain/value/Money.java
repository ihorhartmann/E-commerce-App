package com.hartmann.domain.value;

import com.hartmann.domain.exception.DomainException;

import java.math.BigDecimal;

public record Money(BigDecimal amount, Currency currency) {
    public Money {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException("Amount must not be negative");
        }
    }
}
