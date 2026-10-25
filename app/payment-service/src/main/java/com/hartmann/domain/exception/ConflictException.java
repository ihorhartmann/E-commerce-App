package com.hartmann.domain.exception;

import java.util.Map;

/**
 * A business rule rejecting the operation in the entity's current state, e.g. refunding an unconfirmed
 * payment. Permanent: retrying the same request can't help.
 */
public abstract class ConflictException extends PaymentServiceException {
    protected ConflictException(ErrorCode code, String internalMessage) {
        this(code, internalMessage, Map.of(), null, null);
    }

    protected ConflictException(ErrorCode code, String internalMessage, Map<String, ?> attributes) {
        this(code, internalMessage, attributes, null, null);
    }

    protected ConflictException(ErrorCode code, String internalMessage, Map<String, ?> attributes, Throwable cause) {
        this(code, internalMessage, attributes, null, cause);
    }

    protected ConflictException(
            ErrorCode code,
            String internalMessage,
            Map<String, ?> attributes,
            String externalMessage,
            Throwable cause
    ) {
        super(code, internalMessage, externalMessage, attributes, cause);
    }

    @Override
    public boolean isPermanent() {
        return true;
    }
}
