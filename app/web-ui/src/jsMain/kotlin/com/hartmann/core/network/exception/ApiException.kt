package com.hartmann.core.network.exception

import io.ktor.http.HttpStatusCode

/** A response the API answered with an error status; [message] is fit to be shown to the user. */
open class ApiException(val status: Int, override val message: String) : Exception(message)

/** The session is missing or has expired: the user has to sign in (again). */
class UnauthorizedException :
    ApiException(HttpStatusCode.Unauthorized.value, "Your session has expired. Sign in again.")