package com.hartmann.interfaceadapter.out.http.common

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.time.Duration

/**
 * Only a request that provably never reached the server is retried. Once a request may have been
 * received (a read timeout, a 5xx), the downstream service's idempotency claim for its
 * Idempotency-Key can still be IN_PROGRESS, so a retry would be answered with 409 "request in
 * progress" - which HttpPaymentClient can't tell apart from a genuine business rejection (payment
 * declined: the payment service sends no error codes yet) and would turn into a wrong final order
 * status.
 */
fun isConnectFailure(e: Throwable): Boolean =
    e is ResourceAccessException && generateSequence(e.cause) { it.cause }.any {
        it is ConnectException || it is HttpConnectTimeoutException || it is UnknownHostException
    }

fun connectFailureRetryConfig(maxAttempts: Int, initialBackoff: Duration): RetryConfig =
    RetryConfig.custom<Any>()
        .maxAttempts(maxAttempts)
        .intervalFunction(IntervalFunction.ofExponentialBackoff(initialBackoff, 2.0))
        .retryOnException { isConnectFailure(it) }
        .build()

/**
 * Counts only failures that say the downstream service is unhealthy - unreachable, timing out, or
 * answering 5xx - plus any [additionalFailures] a client has already translated from such a
 * response. A 4xx is a verdict on the request itself and never trips the breaker.
 */
fun httpCircuitBreakerConfig(vararg additionalFailures: Class<out Throwable>): CircuitBreakerConfig =
    CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
        .slidingWindowSize(20)
        .minimumNumberOfCalls(10)
        .failureRateThreshold(50f)
        .waitDurationInOpenState(Duration.ofSeconds(10))
        .permittedNumberOfCallsInHalfOpenState(3)
        .recordExceptions(ResourceAccessException::class.java, HttpServerErrorException::class.java, *additionalFailures)
        .build()
