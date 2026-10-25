package com.hartmann.domain.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The base of every exception this service raises on purpose. Which of its subclasses an exception
 * extends - its kind - states two facts about the failure, {@link #isPermanent()} and {@link #isRetryable()},
 * and the adapters decide from the kind and those facts what to do: the HTTP status, whether a Kafka
 * consumer retries the message or dead-letters it at once, whether the idempotency filter caches
 * the response:
 *
 * <pre>
 * | kind                   | permanent | retryable | HTTP              | Kafka       | idempotency  |
 * |------------------------|-----------|-----------|-------------------|-------------|--------------|
 * | InvalidException       | yes       | no        | 400               | DLT at once | cached       |
 * | NotFoundException      | yes       | no        | 404               | DLT at once | cached       |
 * | ConflictException      | yes       | no        | 409               | DLT at once | cached       |
 * | TransientException     | no        | yes       | 503 + Retry-After | retried     | key released |
 * | UnavailableException   | no        | yes       | 503 + Retry-After | retried     | key released |
 * | anything else          | no        | no        | 500               | retried     | key released |
 * </pre>
 *
 * An internal failure is neither: nothing says repeating can't help - so it is retried, not
 * dead-lettered at once - nor that it will, so a caller isn't invited to retry it.
 * <p>
 * The kinds live here, in the innermost layer, so domain exceptions can extend them too; nothing
 * about them is specific to HTTP or Kafka. Unchecked on purpose: a checked exception would have to be
 * declared through every layer, and {@code @Transactional} rolls back only on unchecked ones by default.
 * <p>
 * A concrete exception passes its own data to the attributes right in its constructor, so each value is
 * a log attribute:
 *
 * <pre>{@code
 * public class PaymentNotFoundException extends NotFoundException {
 *     public PaymentNotFoundException(long paymentId) {
 *         super(RepositoryErrorCode.PAYMENT_NOT_FOUND, "Payment not found", Map.of("paymentId", paymentId));
 *     }
 * }
 * }</pre>
 *
 * internalMessage becomes {@link #getMessage()}; it never leaves the service (logs, dead-letter headers).
 * Keep it constant (no values formatted into it), so every occurrence of one failure logs the same
 * message and can be grouped. attributes are the data of this occurrence - ids, amounts, balances -
 * logged as separate fields next to the message, and never leave the service either; see
 * {@link Attributes#attributesOf}. externalMessage is a message about this occurrence that may leave the
 * service, e.g. which parameter was invalid. Unlike the code's title, which is the same for every
 * occurrence of the code, it is optional and specific to one occurrence.
 */
public abstract class PaymentServiceException extends RuntimeException {
    private final ErrorCode code;
    private final String externalMessage;
    private final Map<String, Object> attributes;

    protected PaymentServiceException(
            ErrorCode code,
            String internalMessage,
            String externalMessage,
            Map<String, ?> attributes,
            Throwable cause
    ) {
        super(internalMessage, cause);
        this.code = code;
        this.externalMessage = externalMessage;
        // not Map.copyOf: it throws on a null value, and an attribute can be null when the request lacks the value
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public ErrorCode code() {
        return code;
    }

    public String externalMessage() {
        return externalMessage;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    /** Whether repeating the same request or message can't change the outcome. */
    public boolean isPermanent() {
        return false;
    }

    /** Whether repeating the same request as it is, with the same Idempotency-Key, is expected to succeed. */
    public boolean isRetryable() {
        return false;
    }
}
