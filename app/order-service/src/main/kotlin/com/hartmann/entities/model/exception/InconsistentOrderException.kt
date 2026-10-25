package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * A stored order breaks the order's own rules - corrupted data, not a request to reject. Thrown with the broken
 * [rule] by [com.hartmann.entities.model.Order.createExisting], or with the exception the order rejected the stored
 * data with as the cause by the repository's mapper - an exception that on its own would read as the caller's fault.
 */
class InconsistentOrderException private constructor(attributes: Map<String, Any?>, cause: Throwable?) :
    OrderServiceException(
        OrderErrorCode.INCONSISTENT_ORDER,
        "Stored order is inconsistent",
        attributes = attributes,
        cause = cause,
    ) {
    constructor(orderId: Long, rule: String) : this(mapOf("orderId" to orderId, "rule" to rule), null)

    constructor(orderId: Long, cause: Throwable) : this(mapOf("orderId" to orderId), cause)
}
