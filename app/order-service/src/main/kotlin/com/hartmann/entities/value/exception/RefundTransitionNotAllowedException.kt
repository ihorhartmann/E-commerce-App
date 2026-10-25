package com.hartmann.entities.value.exception

import com.hartmann.entities.exception.ConflictException

/** The refund was asked to [action] after it had already been confirmed. */
class RefundTransitionNotAllowedException(val action: String) : ConflictException(
    ValueErrorCode.REFUND_TRANSITION_NOT_ALLOWED,
    "Refund transition not allowed",
    mapOf("action" to action),
)
