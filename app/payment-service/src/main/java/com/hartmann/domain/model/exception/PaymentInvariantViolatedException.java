package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.HashMap;
import java.util.Map;

/**
 * This service's own code - or the gateway's answer it passes on - asked the payment for something no caller
 * should, e.g. releasing a refund of another payment: a bug, neither corrupted data (InconsistentPaymentException)
 * nor a request to reject. rule says which rule.
 */
public class PaymentInvariantViolatedException extends PaymentServiceException {
    public PaymentInvariantViolatedException(String rule, Map<String, ?> attributes) {
        super(PaymentErrorCode.PAYMENT_INVARIANT_VIOLATED, "Payment invariant violated", null, withRule(rule, attributes),
                null);
    }

    private static Map<String, Object> withRule(String rule, Map<String, ?> attributes) {
        var withRule = new HashMap<String, Object>(attributes);
        withRule.put("rule", rule);
        return withRule;
    }
}
