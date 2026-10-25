package com.hartmann.domain.exception;

import java.util.Map;

/**
 * A failure that repeating the same request is expected to fix, e.g. a concurrent update that
 * exhausted the service's own retries.
 */
public abstract class TransientException extends PaymentServiceException {
    protected TransientException(ErrorCode code, String internalMessage) {
        this(code, internalMessage, Map.of(), null, null);
    }

    protected TransientException(ErrorCode code, String internalMessage, Map<String, ?> attributes) {
        this(code, internalMessage, attributes, null, null);
    }

    protected TransientException(ErrorCode code, String internalMessage, Map<String, ?> attributes, Throwable cause) {
        this(code, internalMessage, attributes, null, cause);
    }

    protected TransientException(
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
