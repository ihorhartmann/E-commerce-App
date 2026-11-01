package com.hartmann.adapter.in.kafka.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/** The payload isn't valid JSON, or doesn't match the command its message type names. */
public class MalformedPayloadException extends InvalidException {
    public MalformedPayloadException(String messageType, Throwable cause) {
        super(KafkaErrorCode.MALFORMED_MESSAGE, "Malformed message payload", Map.of("messageType", messageType), cause);
    }
}
