package com.hartmann.adapter.in.kafka.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of messages the Kafka consumer rejects before handing them to the services. */
public enum KafkaErrorCode implements ErrorCode {
    INVALID_MESSAGE_HEADER("Invalid message header"),
    MALFORMED_PAYLOAD("Malformed message payload");

    private final String title;

    KafkaErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
