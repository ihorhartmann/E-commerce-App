package com.hartmann.config

import io.micrometer.observation.ObservationPredicate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext

/**
 * Spring Boot reads the OpenTelemetry env vars itself - the collector's address and the `service.name` every span
 * carries - but starts without them too, exporting to localhost and naming itself `unknown_service`. Requiring them
 * here makes a missing one fail the startup instead.
 */
@Configuration
class TracingConfig(environment: Environment) {
    init {
        REQUIRED_ENV_VARS.forEach { environment.getRequiredProperty(it) }
    }

    /** Health checks are polled every few seconds and would otherwise each be a trace of their own. */
    @Bean
    fun skipHealthCheckObservations() = ObservationPredicate { _, context ->
        !(context is ServerRequestObservationContext && context.carrier?.path?.value() == HEALTH_PATH)
    }

    private companion object {
        val REQUIRED_ENV_VARS = listOf("OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_SERVICE_NAME")
        const val HEALTH_PATH = "/health"
    }
}
