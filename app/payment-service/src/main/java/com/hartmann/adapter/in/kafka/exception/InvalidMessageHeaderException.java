package com.hartmann.adapter.in.kafka.exception;

/** A message header is present but its value can't be used, e.g. a messageId that isn't a UUID. */
public class InvalidMessageHeaderException extends RuntimeException {
    public InvalidMessageHeaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
