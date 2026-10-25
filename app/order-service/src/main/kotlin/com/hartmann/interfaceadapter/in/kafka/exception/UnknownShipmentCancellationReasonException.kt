package com.hartmann.interfaceadapter.`in`.kafka.exception

import com.hartmann.entities.exception.InvalidException

class UnknownShipmentCancellationReasonException(reason: String) : InvalidException(
    KafkaErrorCode.UNKNOWN_SHIPMENT_CANCELLATION_REASON,
    "Unknown shipment cancellation reason",
    mapOf("reason" to reason),
)
