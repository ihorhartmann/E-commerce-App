package com.hartmann.interfaceadapter.common.tracing

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertDoesNotThrow

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OpenTelemetryCorrelationTaggerTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracer = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
        .get("test")
    private val tagger = OpenTelemetryCorrelationTagger()

    @Test
    fun `tagCurrentSpan - adds the correlation id to the current span`() {
        val correlationId = "42"
        val span = tracer.spanBuilder("POST /api/v1/orders").startSpan()

        span.makeCurrent().use { tagger.tagCurrentSpan(correlationId) }
        span.end()

        val actual = exporter.finishedSpanItems.single().attributes.get(AttributeKey.stringKey(KafkaTracingAttributes.CONVERSATION_ID))
        assertEquals(correlationId, actual)
    }

    @Test
    fun `tagCurrentSpan - does nothing outside a trace`() {
        assertDoesNotThrow { tagger.tagCurrentSpan("43") }
    }
}
