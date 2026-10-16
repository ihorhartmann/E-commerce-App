package com.hartmann.core.network.exception

/**
 * A response the UI can't make sense of (e.g. a status it doesn't know): a mismatch between the UI and a service, not
 * something the user can act on - so, unlike [ApiException], its [message] is for the console, never shown to the user.
 */
class UnexpectedResponseException(message: String) : Exception(message)
