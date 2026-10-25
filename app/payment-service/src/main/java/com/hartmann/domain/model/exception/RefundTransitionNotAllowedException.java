package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.model.RefundStatus;

import java.util.Map;

/** The refund was asked to action - e.g. submit - in a status that doesn't allow it. */
public class RefundTransitionNotAllowedException extends ConflictException {
    public RefundTransitionNotAllowedException(long refundId, String action, RefundStatus status) {
        super(RefundErrorCode.REFUND_TRANSITION_NOT_ALLOWED, "Refund transition not allowed",
                Map.of("refundId", refundId, "action", action, "status", status));
    }
}
