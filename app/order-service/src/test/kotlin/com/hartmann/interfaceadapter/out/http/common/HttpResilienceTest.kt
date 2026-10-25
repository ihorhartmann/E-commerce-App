package com.hartmann.interfaceadapter.out.http.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.ConnectException
import java.net.ServerSocket
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class HttpResilienceTest {

    @Test
    fun `isConnectFailure - is true for a refused connection`() {
        assertTrue(isConnectFailure(ResourceAccessException("I/O error", ConnectException("Connection refused"))))
    }

    @Test
    fun `isConnectFailure - is true for a connect timeout`() {
        assertTrue(isConnectFailure(ResourceAccessException("I/O error", HttpConnectTimeoutException("connect timed out"))))
    }

    @Test
    fun `isConnectFailure - is true for an unresolvable host`() {
        assertTrue(isConnectFailure(ResourceAccessException("I/O error", UnknownHostException("inventory"))))
    }

    @Test
    fun `isConnectFailure - is true for a connect failure nested deeper in the cause chain`() {
        val cause = java.io.IOException("wrapped", ConnectException("Connection refused"))

        assertTrue(isConnectFailure(ResourceAccessException("I/O error", cause)))
    }

    @Test
    fun `isConnectFailure - is false for a read timeout`() {
        assertFalse(isConnectFailure(ResourceAccessException("I/O error", HttpTimeoutException("request timed out"))))
    }

    @Test
    fun `isConnectFailure - is false for a server error response`() {
        assertFalse(isConnectFailure(HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
    }

    // The two tests below go through a real RestClient on the JDK HttpClient, as HttpClientConfig
    // builds it, to pin down how that stack actually wraps a refused connection and a read timeout

    @Test
    fun `isConnectFailure - is true for a real refused connection`() {
        // Bind and immediately release a port, so nothing is listening on it
        val port = ServerSocket(0).use { it.localPort }

        val e = assertThrows<ResourceAccessException> { restClient(port).get().retrieve().toBodilessEntity() }

        assertTrue(isConnectFailure(e))
    }

    @Test
    fun `isConnectFailure - is false for a real read timeout`() {
        // The OS completes the TCP handshake for a listening socket even though nothing ever accepts
        // or answers on it, so the request is sent and then times out waiting for a response
        ServerSocket(0).use { server ->
            val e = assertThrows<ResourceAccessException> { restClient(server.localPort).get().retrieve().toBodilessEntity() }

            assertFalse(isConnectFailure(e))
        }
    }

    private fun restClient(port: Int): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofMillis(300)) }
        return RestClient.builder().baseUrl("http://localhost:$port").requestFactory(requestFactory).build()
    }
}
