package com.hartmann.application.port.out.repository.exception;

import com.hartmann.domain.exception.NotFoundException;

import java.util.Map;

/** One class for every way a refund is looked up; the attribute says which one this lookup used. */
public class RefundNotFoundException extends NotFoundException {
    private RefundNotFoundException(Map<String, ?> attributes) {
        super(RepositoryErrorCode.REFUND_NOT_FOUND, "Refund not found", attributes);
    }

    public static RefundNotFoundException withId(long refundId) {
        return new RefundNotFoundException(Map.of("refundId", refundId));
    }

    public static RefundNotFoundException withExternalId(String externalId) {
        return new RefundNotFoundException(Map.of("externalId", externalId));
    }
}
