package com.hartmann.usecase.port.common.tracing

/**
 * Marks the span the current work runs in with the saga's correlation id, so every trace of one saga - the order being
 * placed, a cancellation, each Kafka step - is found by that one id.
 */
interface CorrelationTagger {
    /** Adds [correlationId] to the current span; outside a trace, does nothing. */
    fun tagCurrentSpan(correlationId: String)
}
