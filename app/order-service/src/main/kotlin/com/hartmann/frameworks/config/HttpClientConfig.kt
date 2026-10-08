package com.hartmann.frameworks.config

import com.hartmann.interfaceadapter.out.http.HttpInventoryClient
import com.hartmann.interfaceadapter.out.http.HttpPaymentClient
import com.hartmann.interfaceadapter.out.http.LoggingClientHttpRequestInterceptor
import com.hartmann.interfaceadapter.out.http.Resilience4jInventoryClient
import com.hartmann.interfaceadapter.out.http.Resilience4jPaymentClient
import com.hartmann.interfaceadapter.out.http.connectFailureRetryConfig
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.PaymentClient
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
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
    ): InventoryClient = Resilience4jInventoryClient(
        HttpInventoryClient(restClient(baseUrl, readTimeout, httpClient, loggingInterceptor, serviceTokenInterceptor)),
        retry("inventory-service"),
        circuitBreaker("inventory-service", Resilience4jInventoryClient.circuitBreakerConfig()),
    )

    @Bean
    fun paymentClient(
        @Value($$"${payment-service.url}") baseUrl: String,
        @Value($$"${payment-service.read-timeout}") readTimeout: Duration,
        httpClient: HttpClient,
        @Qualifier(LoggingClientHttpRequestInterceptor.BEAN_NAME) loggingInterceptor: ClientHttpRequestInterceptor,
        @Qualifier(ServiceTokenConfig.SERVICE_TOKEN_INTERCEPTOR_BEAN_NAME) serviceTokenInterceptor: ClientHttpRequestInterceptor,
    ): PaymentClient = Resilience4jPaymentClient(
        HttpPaymentClient(restClient(baseUrl, readTimeout, httpClient, loggingInterceptor, serviceTokenInterceptor)),
        retry("payment-service"),
        circuitBreaker("payment-service", Resilience4jPaymentClient.circuitBreakerConfig()),
    )

    private fun restClient(
        baseUrl: String,
        readTimeout: Duration,
        httpClient: HttpClient,
        loggingInterceptor: ClientHttpRequestInterceptor,
        serviceTokenInterceptor: ClientHttpRequestInterceptor,
    ): RestClient {
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(readTimeout) }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .requestInterceptor(loggingInterceptor)
            .requestInterceptor(serviceTokenInterceptor)
            .build()
    }

    private fun retry(name: String): Retry =
        Retry.of(name, connectFailureRetryConfig(MAX_ATTEMPTS, INITIAL_BACKOFF)).apply {
            eventPublisher.onRetry {
                LOG.warn("retrying call to {} after connect failure, attempt={}", name, it.numberOfRetryAttempts, it.lastThrowable)
            }
        }

    private fun circuitBreaker(name: String, config: CircuitBreakerConfig): CircuitBreaker =
        CircuitBreaker.of(name, config).apply {
            eventPublisher.onStateTransition { LOG.warn("{} circuit breaker {}", name, it.stateTransition) }
        }
}
