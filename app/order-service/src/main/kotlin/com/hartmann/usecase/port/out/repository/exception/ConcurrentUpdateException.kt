package com.hartmann.usecase.port.out.repository.exception

import com.hartmann.entities.exception.TransientException

/**
 * The order was changed by someone else since it was loaded at [version], so the update was not applied.
 * Transient: repeating the whole operation reloads it and usually succeeds.
 */
class ConcurrentUpdateException(val orderId: Long, val version: Long, cause: Throwable) : TransientException(
    RepositoryErrorCode.CONCURRENT_UPDATE,
    "Order was changed concurrently",
    mapOf("orderId" to orderId, "version" to version),
    cause = cause,
)
