package com.hartmann.entities.exception

/** A request or message that breaks a validation rule. Permanent: retrying it can't help. */
abstract class InvalidException(
    code: ErrorCode,
    internalMessage: String,
    attributes: Map<String, Any?> = emptyMap(),
    externalMessage: String? = null,
    cause: Throwable? = null,
) : OrderServiceException(code, internalMessage, externalMessage, attributes, cause) {
    override val permanent: Boolean
        get() = true
}
