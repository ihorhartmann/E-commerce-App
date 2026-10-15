package com.hartmann.core.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.dom.TextInput

enum class ButtonStyle(val cssClass: String) {
    PRIMARY("button-primary"),
    SECONDARY("button-secondary"),
    DANGER("button-danger"),
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    enabled: Boolean = true,
) {
    Button({
        type(ButtonType.Button)
        classes("button", style.cssClass)
        if (!enabled) {
            disabled()
        }
        onClick { onClick() }
    }) {
        Text(text)
    }
}

/**
 * A labelled text field; the label is tied to the input by [id], so it also names it for screen readers. With an
 * [error], the field is outlined in red and the error is shown under it.
 */
@Composable
fun Field(
    id: String,
    label: String,
    value: String,
    onInput: (String) -> Unit,
    placeholder: String = "",
    multiline: Boolean = false,
    error: String? = null,
) {
    Div({ classes("field") }) {
        Label(forId = id) { Text(label) }
        if (multiline) {
            TextArea(value) {
                id(id)
                classes("input")
                invalidWhen(error, id)
                placeholder(placeholder)
                onInput { onInput(it.value) }
            }
        } else {
            TextInput(value) {
                id(id)
                classes("input")
                invalidWhen(error, id)
                placeholder(placeholder)
                onInput { onInput(it.value) }
            }
        }
        FieldError(error, id)
    }
}

enum class BadgeTone(val cssClass: String) {
    NEUTRAL("badge-neutral"),
    INFO("badge-info"),
    SUCCESS("badge-success"),
    WARNING("badge-warning"),
    DANGER("badge-danger"),
}

@Composable
fun Badge(text: String, tone: BadgeTone) {
    Span({ classes("badge", tone.cssClass) }) { Text(text) }
}

@Composable
fun Spinner() {
    Div({
        classes("spinner")
        attr("role", "status")
        attr("aria-label", "Loading")
    })
}

@Composable
fun EmptyState(text: String) {
    P({ classes("empty-state") }) { Text(text) }
}

@Composable
fun FormError(message: String?) {
    if (message != null) {
        P({
            classes("form-error")
            attr("role", "alert")
        }) { Text(message) }
    }
}

/** Marks an input invalid (red outline, `aria-invalid`) while there's an [error], tied to it via `aria-describedby`. */
fun AttrsScope<*>.invalidWhen(error: String?, inputId: String) {
    if (error != null) {
        classes("input-invalid")
        attr("aria-invalid", "true")
        attr("aria-describedby", errorId(inputId))
    }
}

/** The message under an invalid input, read out by screen readers along with the input. */
@Composable
fun FieldError(error: String?, inputId: String) {
    if (error != null) {
        P({
            id(errorId(inputId))
            classes("field-error")
        }) { Text(error) }
    }
}

private fun errorId(inputId: String) = "$inputId-error"
