package com.hartmann.http

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.annotation.Order
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.result.view.ViewResolver
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException

/**
 * Turns every request the gateway fails itself into an RFC 9457 ProblemDetail response, looking the same as the
 * services' (`type` `urn:ecommerce:error:<code>`, `title`, `status`, `instance`, `code`, `retryable`, `Retry-After` when
 * retryable): a route that doesn't exist, a service that can't be reached or doesn't answer in time, a failure of its
 * own controller - and, handed over by SecurityConfig, a request that isn't authenticated or fails the CSRF check.
 * Each is logged exactly once, here: a 5xx as an error with its stack trace, anything else as a warning.
 *
 * A service that answers with an error itself is not one of these: its response is passed on as it is.
 */
@Component
@Order(-2) // before Spring Boot's own handler (-1), which would answer in its own format
class GlobalExceptionHandler(codecConfigurer: ServerCodecConfigurer) : ErrorWebExceptionHandler {
    companion object {
        private val LOG = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

        private const val TYPE_URN_PREFIX = "urn:ecommerce:error:"

        /** What a 503 or 504 asks the caller to wait, in seconds. */
        const val RETRY_AFTER_SECONDS = 1L

        const val CODE_PROPERTY = "code"
        const val RETRYABLE_PROPERTY = "retryable"
    }

    private val responseContext = object : ServerResponse.Context {
        override fun messageWriters(): List<HttpMessageWriter<*>> = codecConfigurer.writers
        override fun viewResolvers(): List<ViewResolver> = emptyList()
    }

    override fun handle(exchange: ServerWebExchange, exception: Throwable): Mono<Void> {
        if (exchange.response.isCommitted) {
            return Mono.error(exception)
        }

        val errorCode = errorCodeOf(exception)
        logFailure(exchange, errorCode, exception)

        val problem = ProblemDetail.forStatus(errorCode.status).apply {
            type = URI.create(TYPE_URN_PREFIX + errorCode.code)
            title = errorCode.title
            instance = URI.create(exchange.request.path.value())
            setProperty(CODE_PROPERTY, errorCode.code)
            setProperty(RETRYABLE_PROPERTY, errorCode.retryable)
        }
        return ServerResponse.status(errorCode.status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .headers { if (errorCode.retryable) it.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString()) }
            .bodyValue(problem)
            .flatMap { it.writeTo(exchange, responseContext) }
    }

    private fun errorCodeOf(exception: Throwable): ErrorCode = when {
        exception is OAuth2AuthenticationException -> ErrorCode.INVALID_TOKEN
        exception is AuthenticationException -> ErrorCode.UNAUTHENTICATED
        exception is AccessDeniedException -> ErrorCode.ACCESS_DENIED
        // the service's address doesn't resolve or refuses the connection - or doesn't accept it in time
        causes(exception).any { it is ConnectException || it is UnknownHostException } -> ErrorCode.SERVICE_UNAVAILABLE
        exception is ResponseStatusException -> when (exception.statusCode) {
            HttpStatus.NOT_FOUND -> ErrorCode.ROUTE_NOT_FOUND
            HttpStatus.METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED
            // the service accepted the connection but didn't answer within the response timeout
            HttpStatus.GATEWAY_TIMEOUT -> ErrorCode.SERVICE_TIMEOUT
            HttpStatus.SERVICE_UNAVAILABLE -> ErrorCode.SERVICE_UNAVAILABLE
            else -> if (exception.statusCode.is4xxClientError) ErrorCode.INVALID_REQUEST else ErrorCode.INTERNAL_ERROR
        }

        else -> ErrorCode.INTERNAL_ERROR
    }

    private fun causes(exception: Throwable): Sequence<Throwable> = generateSequence(exception) { it.cause }

    // Every value as a key-value pair of its own, so the structured log format writes each as its own field. A failure
    // on the way to a service also names the route and the service's URL it was sent to.
    private fun logFailure(exchange: ServerWebExchange, error: ErrorCode, exception: Throwable) {
        val event = if (error.status.is5xxServerError) {
            LOG.atError().setCause(exception).setMessage("request failed")
        } else {
            LOG.atWarn().setMessage("http request rejected")
        }

        event.addKeyValue("method", exchange.request.method.name())
            .addKeyValue("path", exchange.request.path.value())
            .addKeyValue("code", error.code)
            .addKeyValue("status", error.status.value())
            .addKeyValue("exceptionMessage", exception.message)
        exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)?.let { event.addKeyValue("route", it.id) }
        exchange.getAttribute<URI>(GATEWAY_REQUEST_URL_ATTR)?.let { event.addKeyValue("targetUri", it) }
        event.log()
    }
}
