package com.hartmann.interfaceadapter.`in`.kafka.exception

import com.hartmann.entities.exception.InvalidException

/** A message header is present but its value can't be used, e.g. a messageId that isn't a UUID. */
class InvalidMessageHeaderException(val header: String, val value: String, cause: Throwable) : InvalidException(
    KafkaErrorCode.INVALID_MESSAGE_HEADER,
    "Invalid message header",
    mapOf("header" to header, "value" to value),
    cause = cause,
)
