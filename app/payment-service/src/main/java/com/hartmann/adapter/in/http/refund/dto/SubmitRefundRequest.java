package com.hartmann.adapter.in.http.refund.dto;

import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;

public record SubmitRefundRequest(long paymentId, BigDecimal amount, String currency) {
    public static Money toMoney(SubmitRefundRequest request) {
        return new Money(request.amount(), Currency.fromCode(request.currency()));
    }
}
