package com.hartmann.interfaceadapter.common.tracing

import com.hartmann.usecase.port.common.tracing.CorrelationTagger
import io.opentelemetry.api.trace.Span
import org.springframework.stereotype.Component

/**
 * Tags the current span with a correlation id that came into being inside it - e.g. the one an order placed over HTTP
 * starts its saga with. A Kafka message brings its correlation id in a header, which the Kafka span conventions tag on
 * their own.
 */
@Component
class OpenTelemetryCorrelationTagger : CorrelationTagger {
    override fun tagCurrentSpan(correlationId: String) {
        Span.current().setAttribute(KafkaTracingAttributes.CONVERSATION_ID, correlationId)
    }
}
