package com.hartmann.adapter.in.kafka.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/** A message header is present but its value can't be used, e.g. a messageId that isn't a UUID. */
public class InvalidMessageHeaderException extends InvalidException {
    public InvalidMessageHeaderException(String header, String value, Throwable cause) {
        super(KafkaErrorCode.INVALID_MESSAGE_HEADER, "Invalid message header", Map.of("header", header, "value", value),
                cause);
    }
}
