package com.hartmann.core.error

/** Decides what the user sees when an action fails. */
interface ErrorHandler {
    fun handle(error: Throwable)
}
