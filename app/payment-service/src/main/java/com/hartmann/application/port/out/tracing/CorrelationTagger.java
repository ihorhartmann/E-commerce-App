package com.hartmann.application.port.out.tracing;

/**
 * Marks the work running now with the saga's correlation id - its span, and every line it logs from here on - for work
 * that learns the id only as it runs, e.g. a Stripe webhook, which brings no correlation id of its own but leads to a
 * payment of one order.
 */
public interface CorrelationTagger {
    /**
     * Adds correlationId to the current span and to the log context of the work running now. A correlation id the work
     * came with - in a Kafka message's or an HTTP request's header - stays in the log context; outside a trace, the span
     * is left alone.
     */
    void tag(String correlationId);
}
