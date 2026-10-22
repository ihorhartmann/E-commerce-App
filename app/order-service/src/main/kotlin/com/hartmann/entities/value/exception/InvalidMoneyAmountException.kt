package com.hartmann.entities.value.exception

import com.hartmann.entities.exception.InvalidException
import java.math.BigDecimal

/** [cause] is the ArithmeticException of an amount with too many decimal places; a negative amount has none. */
class InvalidMoneyAmountException(val amount: BigDecimal, cause: Throwable? = null) : InvalidException(
    ValueErrorCode.INVALID_MONEY_AMOUNT,
    "Invalid money amount",
    mapOf("amount" to amount),
    cause = cause,
)
