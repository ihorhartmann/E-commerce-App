package com.hartmann.adapter.common.tracing;

import com.hartmann.adapter.common.kafka.KafkaConst;

/**
 * Where the saga's correlation id travels outside a Kafka message: in the log context, on a span, and in the HTTP calls
 * between this system's services - shared by the filter that reads it from a request and the tagger that adds it later.
 */
public final class CorrelationConst {
    /** The HTTP header a call between this system's services carries the correlation id in. */
    public static final String HTTP_HEADER = "X-Correlation-Id";

    /** The key of the correlation id in the log context (MDC) - the same as in a Kafka message's headers. */
    public static final String CORRELATION_ID_KEY = KafkaConst.CORRELATION_ID;

    /** The span attribute of the correlation id, as the OpenTelemetry messaging conventions name it. */
    public static final String CONVERSATION_ID_KEY = "messaging.message.conversation_id";

    private CorrelationConst() {
    }
}
