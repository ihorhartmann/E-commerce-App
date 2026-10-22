package com.hartmann.entities.exception

/**
 * The attributes of every [OrderServiceException] in [exception]'s cause chain, merged - the Kotlin
 * counterpart of Go's apperror.LogAttr walking the error chain. An outer exception's value wins over
 * a cause's value for the same key, since the outer layer knows better what the failure meant.
 * Causes that aren't OrderServiceExceptions (a library exception) contribute nothing.
 *
 * A top-level function taking any Throwable rather than a method of [OrderServiceException], against
 * Information Expert, on purpose: the callers often hold an exception that isn't one of ours at the
 * top - Spring Kafka's ListenerExecutionFailedException, a library exception wrapping ours, whatever
 * reached a catch-all handler - so a method would first need our exception found in the chain anyway.
 */
fun attributesOf(exception: Throwable): Map<String, Any?> =
    generateSequence(exception) { it.cause }
        .filterIsInstance<OrderServiceException>()
        .fold(emptyMap()) { attributes, cause -> cause.attributes + attributes }
