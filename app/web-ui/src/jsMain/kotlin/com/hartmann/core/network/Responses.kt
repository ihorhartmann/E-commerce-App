package com.hartmann.core.network

import com.hartmann.core.network.dto.ErrorResponse
import com.hartmann.core.network.exception.ApiException
import com.hartmann.core.network.exception.UnauthorizedException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

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

private suspend fun errorMessage(response: HttpResponse): String = when {
    response.status == HttpStatusCode.Forbidden -> "You don't have access to this."
    // a concurrent update or a dependency being down: repeating the action usually works
    response.status == HttpStatusCode.ServiceUnavailable -> "The service is busy right now. Try again."
    response.status.value >= HttpStatusCode.InternalServerError.value -> "Something went wrong on our side. Try again later."
    else -> try {
        response.body<ErrorResponse>().error
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        // a body that isn't the services' {"error": ...} - fall back to the status
        null
    }
        ?.replaceFirstChar { it.uppercase() }
        ?: "The request was rejected (${response.status.value})."
}
