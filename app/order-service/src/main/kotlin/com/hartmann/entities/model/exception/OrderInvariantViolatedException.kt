package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * This service's own code asked the order for something no caller should - e.g. an order without items - a bug,
 * neither corrupted data ([InconsistentOrderException]) nor a request to reject. [rule] says which rule.
 */
class OrderInvariantViolatedException(rule: String, attributes: Map<String, Any?>) : OrderServiceException(
    OrderErrorCode.ORDER_INVARIANT_VIOLATED,
    "Order invariant violated",
    attributes = attributes + ("rule" to rule),
)
