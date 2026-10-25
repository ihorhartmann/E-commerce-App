package com.hartmann.entities.exception

/** A dependency (database, a sibling service, a third party) that can't be reached. */
abstract class UnavailableException(
    code: ErrorCode,
    internalMessage: String,
    attributes: Map<String, Any?> = emptyMap(),
    externalMessage: String? = null,
    cause: Throwable? = null,
) : OrderServiceException(code, internalMessage, externalMessage, attributes, cause) {
    override val retryable: Boolean
        get() = true
}
