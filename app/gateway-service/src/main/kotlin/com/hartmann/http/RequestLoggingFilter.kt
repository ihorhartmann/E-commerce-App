package com.hartmann.http

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Logs every request on its way in and every successful one on its way out - at debug only: the service a request is
 * routed to logs it too, and its trace has it anyway. A request the gateway fails itself is logged by
 * GlobalExceptionHandler instead; one the service answers with a 5xx gets a debug line here, the service having
 * logged the failure already.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter : WebFilter {
    companion object {
        private val LOG = LoggerFactory.getLogger(RequestLoggingFilter::class.java)
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        LOG.atDebug().setMessage("received http request")
            .addKeyValue("method", exchange.request.method.name())
            .addKeyValue("path", exchange.request.path.value())
            .log()

        return chain.filter(exchange).doOnSuccess { logResponse(exchange) }
    }

    private fun logResponse(exchange: ServerWebExchange) {
        val status = exchange.response.statusCode ?: return
        val event = when {
            status.value() < 400 -> LOG.atDebug().setMessage("successfully handled http request")
            status.is5xxServerError -> LOG.atDebug().setMessage("service responded with an error")
                .addKeyValue("route", exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)?.id)

            else -> return
        }
        event.addKeyValue("method", exchange.request.method.name())
            .addKeyValue("path", exchange.request.path.value())
            .addKeyValue("status", status.value())
            .log()
    }
}
