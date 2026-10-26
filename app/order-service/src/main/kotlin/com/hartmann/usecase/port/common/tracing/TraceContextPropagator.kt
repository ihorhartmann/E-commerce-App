package com.hartmann.usecase.port.common.tracing

/**
 * Carries a trace across a step that is not a direct call, e.g. the outbox: the message stores the traceparent of the
 * span it was written in, and the relay publishes it as a child of that span - otherwise the trace would end there.
 */
interface TraceContextPropagator {
    /** The W3C traceparent of the current span, or null outside a trace. */
    fun currentTraceparent(): String?

    /** Runs [block] as a child of the span [traceparent] names; without one, runs it as it is. */
    fun <T> withParent(traceparent: String?, block: () -> T): T
}
