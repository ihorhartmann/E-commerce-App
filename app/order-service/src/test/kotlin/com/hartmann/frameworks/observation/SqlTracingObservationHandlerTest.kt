package com.hartmann.frameworks.observation

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ttddyy.observation.tracing.QueryContext
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SqlTracingObservationHandlerTest {

    @Test
    fun `createSenderSpan - starts a client span without the data source as its remote service`() {
        val span = mockk<Span>()
        val builder = mockk<Span.Builder>()
        every { builder.kind(any()) } returns builder
        every { builder.start() } returns span
        val tracer = mockk<Tracer>()
        every { tracer.spanBuilder() } returns builder
        every { tracer.currentSpan() } returns null
        val context = QueryContext().apply { remoteServiceName = "ecommerce" }

        val actual = SqlTracingObservationHandler(tracer).createSenderSpan(context)

        assertSame(span, actual)
        verify(exactly = 1) { builder.kind(Span.Kind.CLIENT) }
        verify(exactly = 0) { builder.remoteServiceName(any()) }
        verify(exactly = 1) { builder.start() }
    }
}
