package com.hartmann.domain.value;

import com.hartmann.domain.exception.DomainException;

public record Money(double amount, Currency currency) {
    public Money {
        if (amount < 0) {
            throw new DomainException("Amount must not be negative");
        }
    }
}
