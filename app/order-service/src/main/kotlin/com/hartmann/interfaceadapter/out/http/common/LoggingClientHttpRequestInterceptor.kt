package com.hartmann.interfaceadapter.out.http.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Component

/**
 * Centralizes outbound REST call logging so individual REST clients don't each have to log
 * their own send/response pairs. Shared across every RestClient this service builds.
 */
@Component(LoggingClientHttpRequestInterceptor.BEAN_NAME)
class LoggingClientHttpRequestInterceptor : ClientHttpRequestInterceptor {
    companion object {
        const val BEAN_NAME = "loggingInterceptor"

        private val LOG = LoggerFactory.getLogger(LoggingClientHttpRequestInterceptor::class.java)
    }

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        LOG.debug("sending {} {}", request.method, request.uri)

        val response = execution.execute(request, body)
        if (response.statusCode.is2xxSuccessful) {
            LOG.info("successfully received response {} {} status={}", request.method, request.uri, response.statusCode.value())
        }

        return response
    }
}
