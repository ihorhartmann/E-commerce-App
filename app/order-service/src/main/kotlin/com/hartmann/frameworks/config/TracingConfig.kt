package com.hartmann.frameworks.config

import com.hartmann.frameworks.observation.HttpServerSpanNameConvention
import com.hartmann.frameworks.observation.KafkaListenerSpanNameConvention
import com.hartmann.frameworks.observation.KafkaTemplateSpanNameConvention
import com.hartmann.frameworks.observation.SqlSpanNameConvention
import com.hartmann.frameworks.observation.SqlTracingObservationHandler
import io.micrometer.observation.ObservationPredicate
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.TracingObservationHandler
import io.opentelemetry.api.trace.Span
import net.ttddyy.observation.tracing.DataSourceBaseContext
import net.ttddyy.observation.tracing.QueryObservationConvention
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.http.server.observation.ServerRequestObservationConvention
import org.springframework.kafka.support.micrometer.KafkaListenerObservationConvention
import org.springframework.kafka.support.micrometer.KafkaTemplateObservationConvention

/**
 * Spring Boot reads the OpenTelemetry env vars itself - the collector's address and the `service.name` every span
 * carries - but starts without them too, exporting to localhost and naming itself `unknown_service`. Requiring them
 * here makes a missing one fail the startup instead.
 */
@Configuration
class TracingConfig(environment: Environment) {
    private companion object {
        val REQUIRED_ENV_VARS = listOf("OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_SERVICE_NAME")

        /** The observation Spring opens around every run of a @Scheduled method. */
        const val SCHEDULED_TASK_OBSERVATION = "tasks.scheduled.execution"

        /** The prefix of datasource-micrometer's observations - a SQL statement's is `jdbc.query`. */
        const val JDBC_OBSERVATION_PREFIX = "jdbc."

        // /health and its probes, /health/liveness and /health/readiness
        const val HEALTH_PATH = "/health"
    }

    init {
        REQUIRED_ENV_VARS.forEach { environment.getRequiredProperty(it) }
    }

    /** Health checks and probes are polled every few seconds: neither a trace each, nor a series of the HTTP metrics. */
    @Bean
    fun skipHealthCheckObservations() = ObservationPredicate { _, context ->
        !(context is ServerRequestObservationContext && context.carrier?.requestURI?.startsWith(HEALTH_PATH) == true)
    }

    /**
     * The outbox relay polls every 200ms and the sweeps run on timers: each run would be a trace of its own, nearly
     * always an empty one. What the relay publishes still gets traced, as a child of the span the message was
     * written in.
     */
    @Bean
    fun skipScheduledTaskObservations() = ObservationPredicate { name, _ -> name != SCHEDULED_TASK_OBSERVATION }

    /**
     * A SQL statement is traced only inside a request or a message: run by the outbox relay's poll or a sweep - whose
     * own observation is skipped above - it would have no parent and become a trace of its own, five times a second.
     */
    @Bean
    fun skipJdbcObservationsWithoutParent() = ObservationPredicate { name, _ ->
        !name.startsWith(JDBC_OBSERVATION_PREFIX) || Span.current().spanContext.isValid
    }

    // Span names in one format across all services, the ones OpenTelemetry's semantic conventions give - Spring Boot
    // picks these up for the incoming requests and for the Kafka template and listeners; the outgoing calls get theirs
    // in HttpClientConfig

    @Bean
    fun httpServerSpanNameConvention(): ServerRequestObservationConvention = HttpServerSpanNameConvention()

    @Bean
    fun kafkaTemplateSpanNameConvention(): KafkaTemplateObservationConvention = KafkaTemplateSpanNameConvention()

    @Bean
    fun kafkaListenerSpanNameConvention(): KafkaListenerObservationConvention = KafkaListenerSpanNameConvention()

    @Bean
    fun sqlSpanNameConvention(): QueryObservationConvention = SqlSpanNameConvention()

    /**
     * Declared as a `TracingObservationHandler`, not by its class: as a `PropagatingSenderTracingObservationHandler` it
     * would keep Spring Boot from creating its own, the one that sends the trace context with every HTTP call and Kafka
     * message.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun sqlTracingObservationHandler(tracer: Tracer): TracingObservationHandler<DataSourceBaseContext> =
        SqlTracingObservationHandler(tracer)
}
