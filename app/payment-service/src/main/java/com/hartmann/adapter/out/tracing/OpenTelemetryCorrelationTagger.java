package com.hartmann.adapter.out.tracing;

import com.hartmann.adapter.common.tracing.CorrelationConst;
import com.hartmann.application.port.out.tracing.CorrelationTagger;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Tags the current span and the MDC with a correlation id the work learned as it ran. One a Kafka message brought - put
 * in the MDC by PaymentKafkaConsumer - or one an HTTP request brought in its header - put there by RequestLoggingFilter
 * - stays in the MDC. Whoever started the work removes it from the MDC once it is done.
 */
@Component
public class OpenTelemetryCorrelationTagger implements CorrelationTagger {
    @Override
    public void tag(String correlationId) {
        Span.current().setAttribute(CorrelationConst.CONVERSATION_ID_KEY, correlationId);
        if (MDC.get(CorrelationConst.CORRELATION_ID_KEY) == null) {
            MDC.put(CorrelationConst.CORRELATION_ID_KEY, correlationId);
        }
    }
}
