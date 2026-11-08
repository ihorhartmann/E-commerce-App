package com.hartmann.infrastructure.config;

import com.hartmann.infrastructure.observation.HttpServerSpanNameConvention;
import com.hartmann.infrastructure.observation.KafkaListenerSpanNameConvention;
import com.hartmann.infrastructure.observation.KafkaTemplateSpanNameConvention;
import com.hartmann.infrastructure.observation.SqlSpanNameConvention;
import com.hartmann.infrastructure.observation.SqlTracingObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.opentelemetry.api.trace.Span;
import net.ttddyy.observation.tracing.DataSourceBaseContext;
import net.ttddyy.observation.tracing.QueryObservationConvention;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import org.springframework.kafka.support.micrometer.KafkaListenerObservationConvention;
import org.springframework.kafka.support.micrometer.KafkaTemplateObservationConvention;

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

    private static final String JDBC_OBSERVATION_PREFIX = "jdbc.";

    // /health and its probes, /health/liveness and /health/readiness
    private static final String HEALTH_PATH = "/health";

    public TracingConfig(Environment environment) {
        REQUIRED_ENV_VARS.forEach(environment::getRequiredProperty);
    }

    // Health checks and probes are polled every few seconds: neither a trace each, nor a series of the HTTP metrics.
    @Bean
    public ObservationPredicate skipHealthCheckObservations() {
        return (_, context) -> !(context instanceof ServerRequestObservationContext serverContext
                && serverContext.getCarrier() != null
                && serverContext.getCarrier().getRequestURI().startsWith(HEALTH_PATH));
    }

    // The outbox relay polls every 200ms and the sweeps run on timers: each run would be a trace of its own, nearly
    // always an empty one. What the relay publishes still gets traced, as a child of the span the message was
    // written in.
    @Bean
    public ObservationPredicate skipScheduledTaskObservations() {
        return (name, _) -> !SCHEDULED_TASK_OBSERVATION.equals(name);
    }

    // A SQL statement is traced only inside a request or a message: run by the outbox relay's poll or a sweep - whose
    // own observation is skipped above - it would have no parent and become a trace of its own, five times a second.
    @Bean
    public ObservationPredicate skipJdbcObservationsWithoutParent() {
        return (name, _) -> !name.startsWith(JDBC_OBSERVATION_PREFIX) || Span.current().getSpanContext().isValid();
    }

    // Span names in one format across all services, the ones OpenTelemetry's semantic conventions give - Spring Boot
    // picks these up for the incoming requests and for the Kafka template and listeners.

    @Bean
    public ServerRequestObservationConvention httpServerSpanNameConvention() {
        return new HttpServerSpanNameConvention();
    }

    @Bean
    public KafkaTemplateObservationConvention kafkaTemplateSpanNameConvention() {
        return new KafkaTemplateSpanNameConvention();
    }

    @Bean
    public KafkaListenerObservationConvention kafkaListenerSpanNameConvention() {
        return new KafkaListenerSpanNameConvention();
    }

    @Bean
    public QueryObservationConvention sqlSpanNameConvention() {
        return new SqlSpanNameConvention();
    }

    // Declared as a TracingObservationHandler, not by its class: as a PropagatingSenderTracingObservationHandler it would
    // keep Spring Boot from creating its own, the one that sends the trace context with every HTTP call and Kafka message.
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public TracingObservationHandler<DataSourceBaseContext> sqlTracingObservationHandler(Tracer tracer) {
        return new SqlTracingObservationHandler(tracer);
    }
}
