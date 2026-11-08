package com.hartmann.interfaceadapter.common.tracing

import com.hartmann.usecase.port.common.tracing.CorrelationConst
import com.hartmann.usecase.port.common.tracing.CorrelationTagger
import io.opentelemetry.api.trace.Span
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * Tags the current span and the MDC with a correlation id that came into being inside the work - e.g. the one an order
 * placed over HTTP starts its saga with. A Kafka message brings its correlation id in a header, which the Kafka span
 * conventions tag on their own and the Kafka consumer puts in the MDC; that one, or one an HTTP request brought in its
 * header, stays in the MDC. Whoever started the work removes it from the MDC once it is done - RequestLoggingFilter for
 * an HTTP request, OrderKafkaConsumer for a message.
 */
@Component
class OpenTelemetryCorrelationTagger : CorrelationTagger {
    override fun tag(correlationId: String) {
        Span.current().setAttribute(KafkaTracingAttributes.CONVERSATION_ID, correlationId)
        if (MDC.get(CorrelationConst.CORRELATION_ID_KEY) == null) {
            MDC.put(CorrelationConst.CORRELATION_ID_KEY, correlationId)
        }
    }
}
