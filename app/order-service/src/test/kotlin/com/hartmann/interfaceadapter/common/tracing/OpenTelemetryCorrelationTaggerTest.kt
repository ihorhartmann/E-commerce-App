package com.hartmann.interfaceadapter.common.tracing

import com.hartmann.usecase.port.common.tracing.CorrelationConst
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.MDC

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OpenTelemetryCorrelationTaggerTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracer = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
        .get("test")
    private val tagger = OpenTelemetryCorrelationTagger()

    @AfterEach
    fun clearMdc() {
        MDC.remove(CorrelationConst.CORRELATION_ID_KEY)
    }

    @Test
    fun `tag - adds the correlation id to the current span and to the log context`() {
        val correlationId = "42"
        val span = tracer.spanBuilder("POST /api/v1/orders").startSpan()

        span.makeCurrent().use { tagger.tag(correlationId) }
        span.end()

        val actualSpanCorrelationId =
            exporter.finishedSpanItems.single().attributes.get(AttributeKey.stringKey(KafkaTracingAttributes.CONVERSATION_ID))
        assertEquals(correlationId, actualSpanCorrelationId)
        val actualLogCorrelationId = MDC.get(CorrelationConst.CORRELATION_ID_KEY)
        assertEquals(correlationId, actualLogCorrelationId)
    }

    @Test
    fun `tag - keeps the correlation id the work came with in the log context`() {
        val incomingCorrelationId = "41"
        MDC.put(CorrelationConst.CORRELATION_ID_KEY, incomingCorrelationId)

        tagger.tag("42")

        val actual = MDC.get(CorrelationConst.CORRELATION_ID_KEY)
        assertEquals(incomingCorrelationId, actual)
    }

    @Test
    fun `tag - outside a trace, only adds the correlation id to the log context`() {
        val correlationId = "43"

        tagger.tag(correlationId)

        val actual = MDC.get(CorrelationConst.CORRELATION_ID_KEY)
        assertEquals(correlationId, actual)
    }
}
