package com.hartmann.usecase.port.out.repository.exception

/**
 * The order was changed by someone else since it was loaded, so the update was not applied.
 * Transient: repeating the whole operation reloads it and usually succeeds.
 */
class ConcurrentUpdateException(message: String, cause: Throwable) : RuntimeException(message, cause)
