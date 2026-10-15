package com.hartmann

import com.hartmann.app.App
import com.hartmann.app.AppDependencies
import com.hartmann.core.network.loadConfig
import com.hartmann.core.network.createHttpClient
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import kotlin.coroutines.cancellation.CancellationException

private const val ROOT_ELEMENT_ID = "root"

fun main() {
    val appScope = MainScope()
    val httpClient = createHttpClient()
    appScope.launch {
        val config = try {
            loadConfig(httpClient)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            console.error(error)
            renderComposable(rootElementId = ROOT_ELEMENT_ID) {
                Div({ classes("glass", "panel", "fatal") }) {
                    P { Text("The app couldn't load its configuration. Reload the page in a moment.") }
                }
            }
            return@launch
        }

        val dependencies = AppDependencies(httpClient, config, appScope)
        renderComposable(rootElementId = ROOT_ELEMENT_ID) { App(dependencies) }
    }
}
