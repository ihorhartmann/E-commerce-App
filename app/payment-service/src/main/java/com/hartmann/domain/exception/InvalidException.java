package com.hartmann.domain.exception;

import java.util.Map;

/** A request or message that breaks a validation rule. Permanent: retrying it can't help. */
public abstract class InvalidException extends PaymentServiceException {
    protected InvalidException(ErrorCode code, String internalMessage) {
        this(code, internalMessage, Map.of(), null, null);
    }

    protected InvalidException(ErrorCode code, String internalMessage, Map<String, ?> attributes) {
        this(code, internalMessage, attributes, null, null);
    }

    protected InvalidException(ErrorCode code, String internalMessage, Map<String, ?> attributes, Throwable cause) {
        this(code, internalMessage, attributes, null, cause);
    }

    protected InvalidException(
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
