package com.hartmann.interfaceadapter.`in`.kafka.exception

import com.hartmann.entities.exception.InvalidException

/**
 * A message header every message must have - messageType, messageId or correlationId - is missing, or present but its
 * value can't be used, e.g. a messageId that isn't a UUID.
 */
class InvalidMessageHeaderException private constructor(
    val header: String,
    internalMessage: String,
    attributes: Map<String, Any?>,
    cause: Throwable?,
) : InvalidException(KafkaErrorCode.INVALID_MESSAGE_HEADERS, internalMessage, attributes, cause = cause) {
    /** The header is missing. */
    constructor(header: String) : this(header, "Missing message header", mapOf("header" to header), null)

    /** The header is present but its value can't be used. */
    constructor(header: String, value: String, cause: Throwable) :
        this(header, "Invalid message header", mapOf("header" to header, "value" to value), cause)
}
