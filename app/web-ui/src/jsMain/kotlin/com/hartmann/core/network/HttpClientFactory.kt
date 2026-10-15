package com.hartmann.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private const val CSRF_HEADER = "X-XSRF-TOKEN"
private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
private val MUTATING_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)

/**
 * The one HTTP client of the app. Requests go to the page's own origin, which nginx (or the dev server) forwards to
 * the gateway, so the browser attaches the session cookie on its own.
 */
fun createHttpClient(): HttpClient = HttpClient(Js) {
    // error statuses are turned into ApiException by ensureSuccess/bodyOrThrow, with the body's message
    expectSuccess = false
    defaultRequest {
        url("${window.location.origin}/")
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    // only a request that didn't get any response is repeated: a 5xx may have been handled already, and the
    // services keep its Idempotency-Key claimed for a while, so a quick retry would only be told it's in progress
    install(HttpRequestRetry) {
        retryOnException(maxRetries = 2, retryOnTimeout = true)
        exponentialDelay()
    }
    install(MutatingRequestHeaders)
}

/**
 * Adds to every state-changing request the CSRF token the gateway expects back from a session (cookie) request, and
 * the Idempotency-Key the services require. The key is set once per request: [HttpRequestRetry] resends the same
 * request, so a retry reuses it.
 */
private val MutatingRequestHeaders = createClientPlugin("MutatingRequestHeaders") {
    onRequest { request, _ ->
        if (request.method !in MUTATING_METHODS) {
            return@onRequest
        }
        readCookie(CSRF_COOKIE)?.let { request.headers[CSRF_HEADER] = it }
        if (!request.headers.contains(IDEMPOTENCY_KEY_HEADER)) {
            request.headers[IDEMPOTENCY_KEY_HEADER] = Uuid.random().toString()
        }
    }
}
