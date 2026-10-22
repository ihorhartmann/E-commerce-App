package com.hartmann.entities.exception

/**
 * The base of every exception this service raises on purpose. Which of its subclasses an exception
 * extends - its kind - states two facts about the failure, [permanent] and [retryable], and the
 * adapters decide from the kind and those facts what to do: the HTTP status, whether a Kafka
 * consumer retries the message or dead-letters it at once, whether the idempotency filter caches
 * the response:
 *
 * | kind                   | permanent | retryable | HTTP              | Kafka       | idempotency  |
 * |------------------------|-----------|-----------|-------------------|-------------|--------------|
 * | [InvalidException]     | yes       | no        | 400               | DLT at once | cached       |
 * | [NotFoundException]    | yes       | no        | 404               | DLT at once | cached       |
 * | [ConflictException]    | yes       | no        | 409               | DLT at once | cached       |
 * | [TransientException]   | no        | yes       | 503 + Retry-After | retried     | key released |
 * | [UnavailableException] | no        | yes       | 503 + Retry-After | retried     | key released |
 * | anything else          | no        | no        | 500               | retried     | key released |
 *
 * An internal failure is neither: nothing says repeating can't help - so it is retried, not
 * dead-lettered at once - nor that it will, so a caller isn't invited to retry it.
 *
 * The kinds live here, in the innermost layer, so domain exceptions can extend them too; nothing
 * about them is specific to HTTP or Kafka. Unchecked on purpose: Kotlin has no checked exceptions,
 * and `@Transactional` rolls back only on unchecked ones by default.
 *
 * A concrete exception passes its own data to [attributes] right in its header, so each value is a
 * typed property and a log attribute at once:
 *
 * ```
 * class OrderNotFoundException(val orderId: Long) :
 *     NotFoundException(RepositoryErrorCode.ORDER_NOT_FOUND, "Order not found", mapOf("orderId" to orderId))
 * ```
 *
 * @param internalMessage becomes [message]; never leaves the service (logs, dead-letter headers).
 * Keep it constant (no values formatted into it), so every occurrence of one failure logs the same
 * message and can be grouped.
 * @param attributes the data of this occurrence - ids, amounts, balances - logged as separate
 * fields next to the message, never leave the service; see [attributesOf]
 * @param externalMessage a message about this occurrence that may leave the service, e.g. which
 * parameter was invalid. Unlike the code's title, which is the same for every occurrence of the
 * code, it is optional and specific to one occurrence.
 */
abstract class OrderServiceException(
    val code: ErrorCode,
    internalMessage: String,
    val externalMessage: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(internalMessage, cause) {
    /** Whether repeating the same request or message can't change the outcome. */
    open val permanent: Boolean
        get() = false

    /** Whether repeating the same request as it is, with the same Idempotency-Key, is expected to succeed. */
    open val retryable: Boolean
        get() = false
}
