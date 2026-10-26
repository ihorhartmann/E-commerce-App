package com.hartmann.adapter.out.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OpenTelemetryTraceContextPropagatorTest {
    private final OpenTelemetryTraceContextPropagator propagator =
            new OpenTelemetryTraceContextPropagator(W3CTraceContextPropagator.getInstance());

    @Test
    void currentTraceparent_isNullOutsideATrace() {
        assertNull(propagator.currentTraceparent());
    }

    @Test
    void currentTraceparent_isTheTraceparentOfTheCurrentSpan() {
        var traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        var spanId = "00f067aa0ba902b7";
        var span = Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()));

        String actual;
        try (var ignored = span.makeCurrent()) {
            actual = propagator.currentTraceparent();
        }

        assertEquals("00-" + traceId + "-" + spanId + "-01", actual);
    }

    @Test
    void withParent_makesTheSpanTheTraceparentNamesTheCurrentOneWhileTheBlockRuns() {
        var traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        var actual = propagator.withParent(traceparent, propagator::currentTraceparent);

        assertEquals(traceparent, actual);
        assertNull(propagator.currentTraceparent());
    }

    @Test
    void withParent_runsTheBlockOutsideATraceWhenThereIsNoTraceparent() {
        var spanIsValid = propagator.withParent(null, () -> Span.current().getSpanContext().isValid());

        assertFalse(spanIsValid);
    }
}
