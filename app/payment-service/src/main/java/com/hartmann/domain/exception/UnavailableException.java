package com.hartmann.domain.exception;

import java.util.Map;

/** A dependency (database, a third party) that can't be reached. */
public abstract class UnavailableException extends PaymentServiceException {
    protected UnavailableException(ErrorCode code, String internalMessage) {
        this(code, internalMessage, Map.of(), null, null);
    }

    protected UnavailableException(ErrorCode code, String internalMessage, Map<String, ?> attributes) {
        this(code, internalMessage, attributes, null, null);
    }

    protected UnavailableException(ErrorCode code, String internalMessage, Map<String, ?> attributes, Throwable cause) {
        this(code, internalMessage, attributes, null, cause);
    }

    protected UnavailableException(
            ErrorCode code,
            String internalMessage,
            Map<String, ?> attributes,
            String externalMessage,
            Throwable cause
    ) {
        super(code, internalMessage, externalMessage, attributes, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
