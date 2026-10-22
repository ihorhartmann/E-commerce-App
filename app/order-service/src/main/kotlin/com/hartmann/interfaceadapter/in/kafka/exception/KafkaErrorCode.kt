package com.hartmann.interfaceadapter.`in`.kafka.exception

import com.hartmann.entities.exception.ErrorCode

/** Codes of messages the Kafka consumer rejects before handing them to the saga. */
enum class KafkaErrorCode(override val title: String) : ErrorCode {
    INVALID_MESSAGE_HEADER("Invalid message header"),
    MALFORMED_PAYLOAD("Malformed message payload"),
    UNKNOWN_SHIPMENT_CANCELLATION_REASON("Unknown shipment cancellation reason"),
}
