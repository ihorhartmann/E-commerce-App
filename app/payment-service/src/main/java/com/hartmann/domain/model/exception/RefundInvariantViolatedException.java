package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.HashMap;
import java.util.Map;

/**
 * This service's own code - or the gateway's answer it passes on - asked the refund for something no caller
 * should, e.g. submitting it without the gateway's id: a bug, neither corrupted data (InconsistentRefundException)
 * nor a request to reject. rule says which rule.
 */
public class RefundInvariantViolatedException extends PaymentServiceException {
    public RefundInvariantViolatedException(String rule, Map<String, ?> attributes) {
        super(RefundErrorCode.REFUND_INVARIANT_VIOLATED, "Refund invariant violated", null, withRule(rule, attributes),
                null);
    }

    private static Map<String, Object> withRule(String rule, Map<String, ?> attributes) {
        var withRule = new HashMap<String, Object>(attributes);
        withRule.put("rule", rule);
        return withRule;
    }
}
