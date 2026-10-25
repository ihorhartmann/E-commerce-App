package com.hartmann.core.network

import com.hartmann.core.network.dto.ProblemResponse
import com.hartmann.core.network.exception.ApiException
import com.hartmann.core.network.exception.UnauthorizedException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

// parsed by hand, not by the client's ContentNegotiation: that one is registered for application/json only, and a
// problem comes as application/problem+json
private val problemJsonDecoder = Json { ignoreUnknownKeys = true }

suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
    ensureSuccess()
    return body()
}

/** Turns an error status into an [ApiException] carrying a message fit for the user. */
suspend fun HttpResponse.ensureSuccess() {
    if (status.isSuccess()) {
        return
    }
    if (status == HttpStatusCode.Unauthorized) {
        throw UnauthorizedException()
    }
    throw ApiException(status.value, errorMessage(this))
}

/**
 * The problem's own words - its detail, else its title - for a failure the user can act on: a rejected request
 * (4xx) or one to repeat later (503, e.g. a concurrent update or the payment gateway being down). Any other 5xx is
 * the services' own fault, whose title says only "Internal server error".
 */
private suspend fun errorMessage(response: HttpResponse): String {
    val status = response.status
    if (status.value >= HttpStatusCode.InternalServerError.value && status != HttpStatusCode.ServiceUnavailable) {
        return "Something went wrong on our side. Try again later."
    }
    val problem = problemOf(response)
    return problem?.detail?.takeIf { it.isNotBlank() }
        ?: problem?.title?.takeIf { it.isNotBlank() }
        ?: when (status) {
            // e.g. the gateway rejecting a missing CSRF token, with no problem body
            HttpStatusCode.Forbidden -> "You don't have access to this."
            HttpStatusCode.ServiceUnavailable -> "The service is busy right now. Try again."
            else -> "The request was rejected (${status.value})."
        }
}

/** The response's problem details, or null when its body isn't one - e.g. a proxy's HTML page, or no body at all. */
private suspend fun problemOf(response: HttpResponse): ProblemResponse? =
    try {
        problemJsonDecoder.decodeFromString<ProblemResponse>(response.bodyAsText())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
