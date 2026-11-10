package com.hartmann.core.notification

import kotlinx.coroutines.flow.StateFlow

/** Short messages shown to the user for a few seconds, whichever screen they came from. */
interface Notifier {
    val toasts: StateFlow<List<Toast>>

    fun success(message: String)

    /** Something the user should know but needn't worry about - it went differently than asked, not wrong. */
    fun warning(message: String)

    fun error(message: String)

    fun dismiss(id: Long)
}

data class Toast(val id: Long, val message: String, val kind: ToastKind)

enum class ToastKind {
    SUCCESS,
    WARNING,
    ERROR,
}
