package com.hartmann.interfaceadapter.out.tracing

import com.hartmann.interfaceadapter.common.tracing.OpenTelemetryTraceContextPropagator
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OpenTelemetryTraceContextPropagatorTest {
    private val propagator = OpenTelemetryTraceContextPropagator(W3CTraceContextPropagator.getInstance())

    @Test
    fun `currentTraceparent - is null outside a trace`() {
        assertNull(propagator.currentTraceparent())
    }

    @Test
    fun `currentTraceparent - is the traceparent of the current span`() {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val spanId = "00f067aa0ba902b7"
        val span = Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()))

        val actual = span.makeCurrent().use { propagator.currentTraceparent() }

        assertEquals("00-$traceId-$spanId-01", actual)
    }

    @Test
    fun `withParent - makes the span the traceparent names the current one while the block runs`() {
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

        val actual = propagator.withParent(traceparent) { propagator.currentTraceparent() }

        assertEquals(traceparent, actual)
        assertNull(propagator.currentTraceparent())
    }

    @Test
    fun `withParent - runs the block outside a trace when there is no traceparent`() {
        val spanIsValid = propagator.withParent(null) { Span.current().spanContext.isValid }

        assertFalse(spanIsValid)
    }
}
