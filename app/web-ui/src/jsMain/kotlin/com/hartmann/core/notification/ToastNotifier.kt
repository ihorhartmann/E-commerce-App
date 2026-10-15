package com.hartmann.core.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Keeps each toast for [DISPLAY_MILLIS], then drops it on its own. */
class ToastNotifier private constructor(private val scope: CoroutineScope) : Notifier {
    private val _toasts = MutableStateFlow<List<Toast>>(emptyList())
    override val toasts: StateFlow<List<Toast>> = _toasts
    private var nextId = 0L

    override fun success(message: String) = show(message, isError = false)

    override fun error(message: String) = show(message, isError = true)

    override fun dismiss(id: Long) {
        _toasts.update { toasts -> toasts.filterNot { it.id == id } }
    }

    private fun show(message: String, isError: Boolean) {
        val toast = Toast(nextId++, message, isError)
        _toasts.update { it + toast }
        scope.launch {
            delay(DISPLAY_MILLIS.milliseconds)
            dismiss(toast.id)
        }
    }

    companion object {
        private const val DISPLAY_MILLIS = 10_000L

        fun create(scope: CoroutineScope): Notifier = ToastNotifier(scope)
    }
}
