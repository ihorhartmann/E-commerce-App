package com.hartmann.core.notification

import kotlinx.coroutines.flow.StateFlow

/** Short messages shown to the user for a few seconds, whichever screen they came from. */
interface Notifier {
    val toasts: StateFlow<List<Toast>>

    fun success(message: String)

    fun error(message: String)

    fun dismiss(id: Long)
}

data class Toast(val id: Long, val message: String, val isError: Boolean)
