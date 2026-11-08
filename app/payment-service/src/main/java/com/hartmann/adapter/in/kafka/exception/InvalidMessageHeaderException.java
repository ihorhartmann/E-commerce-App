package com.hartmann.adapter.in.kafka.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/**
 * A message header every message must have - messageType, messageId or correlationId - is missing, or present but its
 * value can't be used, e.g. a messageId that isn't a UUID.
 */
public class InvalidMessageHeaderException extends InvalidException {
    private InvalidMessageHeaderException(String internalMessage, Map<String, ?> attributes, Throwable cause) {
        super(KafkaErrorCode.INVALID_MESSAGE_HEADERS, internalMessage, attributes, cause);
    }

    public static InvalidMessageHeaderException missing(String header) {
        return new InvalidMessageHeaderException("Missing message header", Map.of("header", header), null);
    }

    public static InvalidMessageHeaderException withInvalidValue(String header, String value, Throwable cause) {
        return new InvalidMessageHeaderException("Invalid message header", Map.of("header", header, "value", value),
                cause);
    }
}
