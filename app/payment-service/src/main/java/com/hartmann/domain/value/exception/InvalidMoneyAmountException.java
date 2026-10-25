package com.hartmann.domain.value.exception;

import com.hartmann.domain.exception.InvalidException;

import java.math.BigDecimal;
import java.util.Collections;

/**
 * cause is the ArithmeticException of an amount with too many decimal places; a negative or a missing
 * amount has none.
 */
public class InvalidMoneyAmountException extends InvalidException {
    public InvalidMoneyAmountException(BigDecimal amount) {
        this(amount, null);
    }

    public InvalidMoneyAmountException(BigDecimal amount, Throwable cause) {
        // singletonMap rather than Map.of, which rejects the null of a missing amount
        super(ValueErrorCode.INVALID_MONEY_AMOUNT, "Invalid money amount", Collections.singletonMap("amount", amount),
                cause);
    }
}
