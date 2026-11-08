package com.hartmann.adapter.out.tracing;

import com.hartmann.adapter.common.tracing.CorrelationConst;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OpenTelemetryCorrelationTaggerTest {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetryCorrelationTagger tagger = new OpenTelemetryCorrelationTagger();

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationConst.CORRELATION_ID_KEY);
    }

    @Test
    void tag_addsTheCorrelationIdToTheCurrentSpanAndToTheLogContext() {
        var tracer = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
                .get("test");
        var correlationId = "42";
        var span = tracer.spanBuilder("POST /api/v1/webhooks/stripe").startSpan();

        try (var ignored = span.makeCurrent()) {
            tagger.tag(correlationId);
        }
        span.end();

        var actualSpanCorrelationId = exporter.getFinishedSpanItems().getFirst().getAttributes()
                .get(AttributeKey.stringKey(CorrelationConst.CONVERSATION_ID_KEY));
        assertThat(actualSpanCorrelationId).isEqualTo(correlationId);
        assertThat(MDC.get(CorrelationConst.CORRELATION_ID_KEY)).isEqualTo(correlationId);
    }

    @Test
    void tag_keepsTheCorrelationIdTheWorkCameWithInTheLogContext() {
        var incomingCorrelationId = "41";
        MDC.put(CorrelationConst.CORRELATION_ID_KEY, incomingCorrelationId);

        tagger.tag("42");

        assertThat(MDC.get(CorrelationConst.CORRELATION_ID_KEY)).isEqualTo(incomingCorrelationId);
    }

    @Test
    void tag_outsideATraceOnlyAddsTheCorrelationIdToTheLogContext() {
        var correlationId = "43";

        tagger.tag(correlationId);

        assertThat(MDC.get(CorrelationConst.CORRELATION_ID_KEY)).isEqualTo(correlationId);
    }
}
