package com.hartmann.core.error

import com.hartmann.core.network.exception.ApiException
import com.hartmann.core.network.exception.UnauthorizedException
import com.hartmann.core.auth.AuthFlowLauncher
import com.hartmann.core.notification.Notifier

/**
 * An expired session sends the user to sign in again; a request the API rejected shows the API's message; anything
 * else - no connection, a response the UI doesn't understand, a bug - gets a neutral message, with the details in the
 * console.
 */
class NotifyingErrorHandler private constructor(
    private val notifier: Notifier,
    private val authFlowLauncher: AuthFlowLauncher,
) : ErrorHandler {
    override fun handle(error: Throwable) {
        when (error) {
            is UnauthorizedException -> authFlowLauncher.signIn()
            is ApiException -> notifier.error(error.message)
            else -> {
                console.error(error)
                notifier.error("Something went wrong. Try again.")
            }
        }
    }

    companion object {
        fun create(notifier: Notifier, authFlowLauncher: AuthFlowLauncher): ErrorHandler =
            NotifyingErrorHandler(notifier, authFlowLauncher)
    }
}
