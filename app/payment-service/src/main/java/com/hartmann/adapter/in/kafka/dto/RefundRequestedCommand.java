package com.hartmann.adapter.in.kafka.dto;

import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;

public record RefundRequestedCommand(long orderId, BigDecimal amount, String currency) {
    public static Money toMoney(RefundRequestedCommand command) {
        return new Money(command.amount(), Currency.fromCode(command.currency()));
    }
}
