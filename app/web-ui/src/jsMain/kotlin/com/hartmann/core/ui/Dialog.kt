package com.hartmann.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** A modal glass panel; Escape or a click on the backdrop closes it. */
@Composable
fun Dialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    DisposableEffect(onClose) {
        val onKeyDown: (Event) -> Unit = { event ->
            if ((event as KeyboardEvent).key == "Escape") {
                onClose()
            }
        }
        document.addEventListener("keydown", onKeyDown)
        onDispose { document.removeEventListener("keydown", onKeyDown) }
    }
    Div({
        classes("dialog-backdrop")
        onClick { event ->
            if (event.target == event.currentTarget) {
                onClose()
            }
        }
    }) {
        Div({
            classes("glass", "dialog")
            attr("role", "dialog")
            attr("aria-modal", "true")
            attr("aria-label", title)
        }) {
            H2({ classes("dialog-title") }) { Text(title) }
            content()
        }
    }
}
