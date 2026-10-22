package com.hartmann.entities.exception

/**
 * A business rule rejecting the operation in the entity's current state, e.g. cancelling a shipped
 * order. Permanent: retrying the same request can't help.
 */
abstract class ConflictException(
    code: ErrorCode,
    internalMessage: String,
    attributes: Map<String, Any?> = emptyMap(),
    externalMessage: String? = null,
    cause: Throwable? = null,
) : OrderServiceException(code, internalMessage, externalMessage, attributes, cause) {
    override val permanent: Boolean
        get() = true
}
