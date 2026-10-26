package com.hartmann.infrastructure.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Spring Boot reads the OpenTelemetry env vars itself - the collector's address and the service.name every span
 * carries - but starts without them too, exporting to localhost and naming itself unknown_service. Requiring them
 * here makes a missing one fail the startup instead.
 */
@Configuration
public class TracingConfig {
    private static final List<String> REQUIRED_ENV_VARS = List.of("OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_SERVICE_NAME");

    // The observation Spring opens around every run of a @Scheduled method.
    private static final String SCHEDULED_TASK_OBSERVATION = "tasks.scheduled.execution";

    public TracingConfig(Environment environment) {
        REQUIRED_ENV_VARS.forEach(environment::getRequiredProperty);
    }

    // The outbox relay polls every 200ms and the sweeps run on timers: each run would be a trace of its own, nearly
    // always an empty one. What the relay publishes still gets traced, as a child of the span the message was
    // written in.
    @Bean
    public ObservationPredicate skipScheduledTaskObservations() {
        return (name, _) -> !SCHEDULED_TASK_OBSERVATION.equals(name);
    }
}
