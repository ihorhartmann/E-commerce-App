package com.hartmann.interfaceadapter.out.repository.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * A stored order breaks the order's own rules - corrupted data, not a request to reject. Its cause is the
 * exception the order rejected the data with, which on its own would read as the caller's fault.
 */
class InconsistentOrderException(val orderId: Long, cause: Throwable) : OrderServiceException(
    RepositoryErrorCode.INCONSISTENT_ORDER,
    "Stored order is inconsistent",
    attributes = mapOf("orderId" to orderId),
    cause = cause,
)
