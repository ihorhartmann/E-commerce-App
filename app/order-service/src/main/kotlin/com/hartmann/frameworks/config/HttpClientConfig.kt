package com.hartmann.frameworks.config

import com.hartmann.frameworks.observation.HttpClientSpanNameConvention
import com.hartmann.interfaceadapter.out.http.common.LoggingClientHttpRequestInterceptor
import com.hartmann.interfaceadapter.out.http.common.connectFailureRetryConfig
import com.hartmann.interfaceadapter.out.http.inventory.HttpInventoryClient
import com.hartmann.interfaceadapter.out.http.inventory.Resilience4jInventoryClient
import com.hartmann.interfaceadapter.out.http.payment.HttpPaymentClient
import com.hartmann.interfaceadapter.out.http.payment.Resilience4jPaymentClient
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.PaymentClient
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.github.resilience4j.retry.Retry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class HttpClientConfig {
    companion object {
        private val LOG = LoggerFactory.getLogger(HttpClientConfig::class.java)
        private const val MAX_ATTEMPTS = 3
        private val INITIAL_BACKOFF = Duration.ofMillis(200)
    }

    @Bean
    fun httpClient(@Value($$"${http-client.connect-timeout}") connectTimeout: Duration): HttpClient =
        HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    @Bean
    fun inventoryClient(
        @Value($$"${inventory-service.url}") baseUrl: String,
        @Value($$"${inventory-service.read-timeout}") readTimeout: Duration,
        httpClient: HttpClient,
        @Qualifier(LoggingClientHttpRequestInterceptor.BEAN_NAME) loggingInterceptor: ClientHttpRequestInterceptor,
        @Qualifier(ServiceTokenConfig.SERVICE_TOKEN_INTERCEPTOR_BEAN_NAME) serviceTokenInterceptor: ClientHttpRequestInterceptor,
        observationRegistry: ObservationRegistry,
        meterRegistry: MeterRegistry,
    ): InventoryClient = Resilience4jInventoryClient(
        HttpInventoryClient(restClient(baseUrl, readTimeout, httpClient, loggingInterceptor, serviceTokenInterceptor, observationRegistry)),
        retry("inventory-service"),
        circuitBreaker("inventory-service", Resilience4jInventoryClient.circuitBreakerConfig(), meterRegistry),
    )

    @Bean
    fun paymentClient(
        @Value($$"${payment-service.url}") baseUrl: String,
        @Value($$"${payment-service.read-timeout}") readTimeout: Duration,
        httpClient: HttpClient,
        @Qualifier(LoggingClientHttpRequestInterceptor.BEAN_NAME) loggingInterceptor: ClientHttpRequestInterceptor,
        @Qualifier(ServiceTokenConfig.SERVICE_TOKEN_INTERCEPTOR_BEAN_NAME) serviceTokenInterceptor: ClientHttpRequestInterceptor,
        observationRegistry: ObservationRegistry,
        meterRegistry: MeterRegistry,
    ): PaymentClient = Resilience4jPaymentClient(
        HttpPaymentClient(restClient(baseUrl, readTimeout, httpClient, loggingInterceptor, serviceTokenInterceptor, observationRegistry)),
        retry("payment-service"),
        circuitBreaker("payment-service", Resilience4jPaymentClient.circuitBreakerConfig(), meterRegistry),
    )

    private fun restClient(
        baseUrl: String,
        readTimeout: Duration,
        httpClient: HttpClient,
        loggingInterceptor: ClientHttpRequestInterceptor,
        serviceTokenInterceptor: ClientHttpRequestInterceptor,
        observationRegistry: ObservationRegistry,
    ): RestClient {
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(readTimeout) }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            // a client span per call, and the traceparent header that lets the callee continue the trace
            .observationRegistry(observationRegistry)
            .observationConvention(HttpClientSpanNameConvention())
            .requestInterceptor(loggingInterceptor)
            .requestInterceptor(serviceTokenInterceptor)
            .build()
    }

    private fun retry(name: String): Retry =
        Retry.of(name, connectFailureRetryConfig(MAX_ATTEMPTS, INITIAL_BACKOFF)).apply {
            eventPublisher.onRetry {
                LOG.atWarn().setMessage("retrying http call after connect failure")
                    .setCause(it.lastThrowable)
                    .addKeyValue("client", name)
                    .addKeyValue("attempt", it.numberOfRetryAttempts)
                    .log()
            }
        }

    // Its state, calls and failure rate as resilience4j.circuitbreaker.* metrics, tagged with its name - Resilience4j
    // binds the breakers of a registry, so each gets a registry of its own.
    private fun circuitBreaker(name: String, config: CircuitBreakerConfig, meterRegistry: MeterRegistry): CircuitBreaker {
        val registry = CircuitBreakerRegistry.ofDefaults()
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry)
        return registry.circuitBreaker(name, config).apply {
            eventPublisher.onStateTransition {
                LOG.atWarn().setMessage("circuit breaker state changed")
                    .addKeyValue("client", name)
                    .addKeyValue("fromState", it.stateTransition.fromState)
                    .addKeyValue("toState", it.stateTransition.toState)
                    .log()
            }
        }
    }
}
