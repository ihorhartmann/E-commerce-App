package com.hartmann.core.network

import com.hartmann.core.network.dto.AppConfigResponse
import com.hartmann.core.network.bodyOrThrow
import io.ktor.client.HttpClient
import io.ktor.client.request.get

data class AppConfig(val stripePublishableKey: String)

suspend fun loadConfig(client: HttpClient): AppConfig =
    AppConfig(client.get("config.json").bodyOrThrow<AppConfigResponse>().stripePublishableKey)
