package com.hartmann.usecase.port.common.tracing

/**
 * Marks the work running now with the saga's correlation id - its span, and every line it logs from here on - so every
 * trace and every log line of one saga - the order being placed, a cancellation, each Kafka step - is found by that one
 * id.
 */
interface CorrelationTagger {
    /**
     * Adds [correlationId] to the current span and to the log context of the work running now. A correlation id the work
     * came with - in a Kafka message's or an HTTP request's header - stays in the log context; outside a trace, the span
     * is left alone.
     */
    fun tag(correlationId: String)
}
