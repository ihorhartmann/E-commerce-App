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
        LOG.atDebug().setMessage("sending http request")
            .addKeyValue("method", request.method)
            .addKeyValue("uri", request.uri)
            .log()

        val response = execution.execute(request, body)
        if (response.statusCode.is2xxSuccessful) {
            LOG.atInfo().setMessage("successfully received http response")
                .addKeyValue("method", request.method)
                .addKeyValue("uri", request.uri)
                .addKeyValue("status", response.statusCode.value())
                .log()
        }

        return response
    }
}
