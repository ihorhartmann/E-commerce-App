package com.hartmann.adapter.in.http.payment.dto;

import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;

public record SubmitPaymentRequest(long orderId, BigDecimal amount, String currency) {
    public static Money toMoney(SubmitPaymentRequest request) {
        return new Money(request.amount(), Currency.fromCode(request.currency()));
    }
}
