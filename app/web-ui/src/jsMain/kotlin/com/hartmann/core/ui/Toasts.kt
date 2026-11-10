package com.hartmann.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hartmann.core.notification.Notifier
import com.hartmann.core.notification.ToastKind
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
fun Toasts(notifier: Notifier) {
    val toasts by notifier.toasts.collectAsState()
    Div({
        classes("toasts")
        attr("aria-live", "polite")
    }) {
        toasts.forEach { toast ->
            Div({
                classes("glass", "toast", toast.kind.cssClass())
                // an error interrupts the screen reader; the rest waits its turn
                attr("role", if (toast.kind == ToastKind.ERROR) "alert" else "status")
            }) {
                Span { Text(toast.message) }
                Button({
                    type(ButtonType.Button)
                    classes("toast-close")
                    attr("aria-label", "Dismiss")
                    onClick { notifier.dismiss(toast.id) }
                }) { Text("×") }
            }
        }
    }
}

private fun ToastKind.cssClass(): String = when (this) {
    ToastKind.SUCCESS -> "toast-success"
    ToastKind.WARNING -> "toast-warning"
    ToastKind.ERROR -> "toast-error"
}
