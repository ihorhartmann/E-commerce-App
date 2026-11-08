package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.exception.attributesOf
import com.hartmann.entities.exception.outermostOf
import org.slf4j.MDC

/**
 * Whether a message whose handling failed with [exception] can't succeed on any later attempt, so it is
 * dead-lettered at once instead of retried: decided by the outermost OrderServiceException in its cause chain -
 * the top of the chain is the listener container's own wrapper. A failure without one is retried.
 */
fun isPermanentFailure(exception: Throwable): Boolean =
    outermostOf(exception)?.permanent == true

/**
 * The key-values a log line describes a failed message's [exception] with: the message of the outermost
 * OrderServiceException in its cause chain - or of [exception], without one - its code, and the attributes of the chain.
 * A key the MDC has already - the message's `messageType`, say, an attribute of a MalformedPayloadException too - is
 * left out: Spring Boot's structured logging drops a line that has a key twice.
 */
fun failureKeyValues(exception: Throwable): Map<String, Any?> {
    val outermost = outermostOf(exception)
    val keyValues = buildMap {
        put("exceptionMessage", (outermost ?: exception).message)
        outermost?.let { put("code", it.code.code) }
        putAll(attributesOf(exception))
    }
    return keyValues.filterKeys { MDC.get(it) == null }
}
