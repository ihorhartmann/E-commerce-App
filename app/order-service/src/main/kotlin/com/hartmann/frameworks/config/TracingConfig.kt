package com.hartmann.frameworks.config

import com.hartmann.frameworks.observation.HttpServerSpanNameConvention
import com.hartmann.frameworks.observation.KafkaListenerSpanNameConvention
import com.hartmann.frameworks.observation.KafkaTemplateSpanNameConvention
import io.micrometer.observation.ObservationPredicate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
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
    }

    init {
        REQUIRED_ENV_VARS.forEach { environment.getRequiredProperty(it) }
    }

    /**
     * The outbox relay polls every 200ms and the sweeps run on timers: each run would be a trace of its own, nearly
     * always an empty one. What the relay publishes still gets traced, as a child of the span the message was
     * written in.
     */
    @Bean
    fun skipScheduledTaskObservations() = ObservationPredicate { name, _ -> name != SCHEDULED_TASK_OBSERVATION }

    // Span names in one format across all services, the ones OpenTelemetry's semantic conventions give - Spring Boot
    // picks these up for the incoming requests and for the Kafka template and listeners; the outgoing calls get theirs
    // in HttpClientConfig

    @Bean
    fun httpServerSpanNameConvention(): ServerRequestObservationConvention = HttpServerSpanNameConvention()

    @Bean
    fun kafkaTemplateSpanNameConvention(): KafkaTemplateObservationConvention = KafkaTemplateSpanNameConvention()

    @Bean
    fun kafkaListenerSpanNameConvention(): KafkaListenerObservationConvention = KafkaListenerSpanNameConvention()
}
