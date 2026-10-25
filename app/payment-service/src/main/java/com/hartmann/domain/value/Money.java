package com.hartmann.domain.value;

import com.hartmann.domain.value.exception.CurrencyMismatchException;
import com.hartmann.domain.value.exception.InvalidMoneyAmountException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A money amount, always stored with exactly SCALE decimal places, so two amounts are equal exactly
 * when they are numerically equal (20 becomes 20.00). An amount with more decimal places is rejected
 * rather than rounded.
 */
public record Money(BigDecimal amount, Currency currency) {
    private static final int SCALE = 2;

    public Money {
        // e.g. a request or a message without an amount
        if (amount == null) {
            throw new InvalidMoneyAmountException(null);
        }
        try {
            amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidMoneyAmountException(amount, e);
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidMoneyAmountException(amount);
        }
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public void requireSameCurrency(Money other) {
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
