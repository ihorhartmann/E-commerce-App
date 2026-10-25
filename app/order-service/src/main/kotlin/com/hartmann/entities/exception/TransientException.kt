package com.hartmann.entities.exception

/**
 * A failure that repeating the same request is expected to fix, e.g. a concurrent update that
 * exhausted the service's own retries.
 */
abstract class TransientException(
    code: ErrorCode,
    internalMessage: String,
    attributes: Map<String, Any?> = emptyMap(),
    externalMessage: String? = null,
    cause: Throwable? = null,
) : OrderServiceException(code, internalMessage, externalMessage, attributes, cause) {
    override val retryable: Boolean
        get() = true
}
