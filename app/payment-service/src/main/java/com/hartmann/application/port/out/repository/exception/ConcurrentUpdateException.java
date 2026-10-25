package com.hartmann.application.port.out.repository.exception;

import com.hartmann.domain.exception.TransientException;

import java.util.Map;

/**
 * The object was changed by someone else since it was loaded at version, so the update was not applied.
 * Transient: repeating the whole operation reloads the object and usually succeeds.
 */
public class ConcurrentUpdateException extends TransientException {
    private ConcurrentUpdateException(Map<String, ?> attributes) {
        super(RepositoryErrorCode.CONCURRENT_UPDATE, "Object was changed concurrently", attributes);
    }

    public static ConcurrentUpdateException ofPayment(long paymentId, long version) {
        return new ConcurrentUpdateException(Map.of("paymentId", paymentId, "version", version));
    }

    public static ConcurrentUpdateException ofRefund(long refundId, long version) {
        return new ConcurrentUpdateException(Map.of("refundId", refundId, "version", version));
    }
}
