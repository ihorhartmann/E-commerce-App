package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.exception.OrderServiceException

/**
 * Whether a message whose handling failed with [exception] can't succeed on any later attempt, so it is
 * dead-lettered at once instead of retried: decided by the outermost OrderServiceException in its cause chain -
 * the top of the chain is the listener container's own wrapper. A failure without one is retried.
 */
fun isPermanentFailure(exception: Throwable): Boolean =
    generateSequence(exception) { it.cause }.filterIsInstance<OrderServiceException>().firstOrNull()?.permanent == true
