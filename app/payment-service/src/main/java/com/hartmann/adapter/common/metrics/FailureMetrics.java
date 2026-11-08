package com.hartmann.adapter.common.metrics;

import io.micrometer.core.instrument.Metrics;

/**
 * Counts failed HTTP requests and Kafka message attempts by their code - a conflict on an optimistic lock, a request
 * already in progress, a payment the gateway rejected - which the status or the outcome alone doesn't tell apart. The
 * other services count theirs under the same name. Recorded in Micrometer's global registry, which Spring Boot adds
 * its own registry to - without one, e.g. in a unit test, nothing is recorded.
 */
public final class FailureMetrics {
    public static final String TRANSPORT_HTTP = "http";
    public static final String TRANSPORT_KAFKA = "kafka";

    private static final String FAILURES = "ecommerce.errors";

    private FailureMetrics() {
    }

    public static void recordFailureMetric(String code, String transport) {
        Metrics.counter(FAILURES, "error.code", code, "transport", transport).increment();
    }
}
