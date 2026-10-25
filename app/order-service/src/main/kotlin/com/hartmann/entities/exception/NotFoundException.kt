package com.hartmann.entities.exception

/**
 * A referenced entity that doesn't exist, or belongs to another customer. Permanent: retrying it
 * can't help - a Kafka consumer for which "not found" only means "not there yet" translates it into
 * a [TransientException] itself.
 */
abstract class NotFoundException(
    code: ErrorCode,
    internalMessage: String,
    attributes: Map<String, Any?> = emptyMap(),
    externalMessage: String? = null,
    cause: Throwable? = null,
) : OrderServiceException(code, internalMessage, externalMessage, attributes, cause) {
    override val permanent: Boolean
        get() = true
}
