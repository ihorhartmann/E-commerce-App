package com.hartmann.core.network

import com.hartmann.core.network.dto.AppConfigResponse
import io.ktor.client.*
import io.ktor.client.request.*

data class AppConfig(val stripePublishableKey: String)

suspend fun loadConfig(client: HttpClient): AppConfig =
    AppConfig(client.get("config.json").bodyOrThrow<AppConfigResponse>().stripePublishableKey)
