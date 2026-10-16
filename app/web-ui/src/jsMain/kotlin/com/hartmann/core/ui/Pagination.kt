package com.hartmann.core.ui

import androidx.compose.runtime.Composable
import com.hartmann.core.model.Page
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** Hidden when everything fits on one page. */
@Composable
fun Pagination(page: Page<*>, onPage: (Int) -> Unit) {
    if (page.totalPages <= 1) {
        return
    }
    Div({ classes("pagination") }) {
        AppButton("Previous", { onPage(page.number - 1) }, enabled = page.hasPrevious())
        Span({ classes("pagination-label") }) { Text("Page ${page.number + 1} of ${page.totalPages}") }
        AppButton("Next", { onPage(page.number + 1) }, enabled = page.hasNext())
    }
}
