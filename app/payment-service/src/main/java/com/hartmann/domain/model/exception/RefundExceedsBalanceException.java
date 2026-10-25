package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.value.Money;

import java.util.Map;

/** The refund asks for more than what is left of the payment after its earlier refunds. */
public class RefundExceedsBalanceException extends ConflictException {
    public RefundExceedsBalanceException(long paymentId, Money amount, Money refundableBalance) {
        super(PaymentErrorCode.REFUND_EXCEEDS_BALANCE, "Refund exceeds the refundable balance", Map.of(
                "paymentId", paymentId,
                "amount", amount.amount(),
                "refundableBalance", refundableBalance.amount(),
                "currency", amount.currency()));
    }
}
