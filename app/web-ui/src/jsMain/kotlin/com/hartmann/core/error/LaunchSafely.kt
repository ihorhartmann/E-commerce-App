package com.hartmann.core.error

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Launches [block], handing any failure to [errorHandler] instead of crashing the scope. A cancellation is no failure:
 * it ends the coroutine as usual (e.g. when the user leaves the screen whose scope it runs in).
 */
fun CoroutineScope.launchSafely(errorHandler: ErrorHandler, block: suspend CoroutineScope.() -> Unit): Job = launch {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        errorHandler.handle(error)
    }
}
