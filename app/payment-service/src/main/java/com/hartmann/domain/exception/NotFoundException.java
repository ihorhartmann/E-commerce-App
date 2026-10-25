package com.hartmann.domain.exception;

import java.util.Map;

/**
 * A referenced entity that doesn't exist. Permanent: retrying it can't help - a Kafka consumer for which
 * "not found" only means "not there yet" translates it into a {@link TransientException} itself.
 */
public abstract class NotFoundException extends PaymentServiceException {
    protected NotFoundException(ErrorCode code, String internalMessage) {
        this(code, internalMessage, Map.of(), null, null);
    }

    protected NotFoundException(ErrorCode code, String internalMessage, Map<String, ?> attributes) {
        this(code, internalMessage, attributes, null, null);
    }

    protected NotFoundException(ErrorCode code, String internalMessage, Map<String, ?> attributes, Throwable cause) {
        this(code, internalMessage, attributes, null, cause);
    }

    protected NotFoundException(
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
