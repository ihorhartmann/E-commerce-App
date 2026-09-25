package com.hartmann.adapter.in.kafka.exception;

/**
 * Marks a message-handling failure that repeats identically on every attempt (a malformed payload, a
 * business rule rejecting the message, ...): the error handler sends such a message straight to its
 * dead-letter topic instead of retrying it. Any failure that isn't wrapped in it is treated as
 * transient and retried. The original failure is kept as the cause.
 */
public class NonRetryableException extends RuntimeException {
    public NonRetryableException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
