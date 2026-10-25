package com.hartmann.domain.value.exception;

import com.hartmann.domain.exception.InvalidException;
import com.hartmann.domain.value.Currency;

import java.util.Map;

/** Two amounts in different currencies were combined or compared, e.g. a refund in another currency than its payment. */
public class CurrencyMismatchException extends InvalidException {
    public CurrencyMismatchException(Currency currency, Currency otherCurrency) {
        super(ValueErrorCode.CURRENCY_MISMATCH, "Currency mismatch",
                Map.of("currency", currency, "otherCurrency", otherCurrency));
    }
}
