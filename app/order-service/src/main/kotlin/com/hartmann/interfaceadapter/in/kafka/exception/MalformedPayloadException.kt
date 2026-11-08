package com.hartmann.interfaceadapter.`in`.kafka.exception

import com.hartmann.entities.exception.InvalidException

/** The payload isn't valid JSON, or doesn't match the event its message type names. */
class MalformedPayloadException(val messageType: String, cause: Throwable) : InvalidException(
    KafkaErrorCode.MALFORMED_MESSAGE,
    "Malformed message payload",
    mapOf("messageType" to messageType),
    cause = cause,
)
