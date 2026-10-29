package com.hartmann.application.port.out.tracing;

import java.util.function.Supplier;

/**
 * Carries a trace across a step that is not a direct call, e.g. the outbox: the message stores the traceparent of the
 * span it was written in, and the relay publishes it as a child of that span - otherwise the trace would end there.
 */
public interface TraceContextPropagator {
    /** The W3C traceparent of the current span, or null outside a trace. */
    String currentTraceparent();

    /** Runs block as a child of the span traceparent names; without one (null), runs it as it is. */
    <T> T withParent(String traceparent, Supplier<T> block);
}
