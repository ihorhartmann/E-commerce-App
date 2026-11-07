package com.hartmann.interfaceadapter.common.metrics

import io.micrometer.core.instrument.Metrics

/** The transport a failure came in over. */
const val TRANSPORT_HTTP = "http"
const val TRANSPORT_KAFKA = "kafka"

private const val FAILURES = "ecommerce.errors"

/**
 * Counts one failed HTTP request or Kafka message attempt by its [code] - a conflict on an optimistic lock, a request
 * already in progress, a payment the gateway rejected - which the status or the outcome alone doesn't tell apart. The
 * Go services count theirs under the same name. Recorded in Micrometer's global registry, which Spring Boot adds its
 * own registry to - without one, e.g. in a unit test, nothing is recorded.
 */
fun recordFailureMetric(code: String, transport: String) {
    Metrics.counter(FAILURES, "error.code", code, "transport", transport).increment()
}
