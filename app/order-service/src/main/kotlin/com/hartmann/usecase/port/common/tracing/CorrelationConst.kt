package com.hartmann.usecase.port.common.tracing

import com.hartmann.usecase.port.common.kafka.KafkaConst

/**
 * Where the saga's correlation id travels outside a Kafka message: in the log context, and in the HTTP calls between this
 * system's services - shared by the filter that reads it from a request and the client interceptor that sends it on.
 */
object CorrelationConst {
    /** The HTTP header a call between this system's services carries the correlation id in. */
    const val HTTP_HEADER = "X-Correlation-Id"

    /** The key of the correlation id in the log context (MDC) - the same as in a Kafka message's headers. */
    const val CORRELATION_ID_KEY = KafkaConst.CORRELATION_ID
}
