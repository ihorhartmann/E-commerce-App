package com.hartmann.core.network.dto

import kotlinx.serialization.Serializable

/** `/config.json`: values that differ per environment, filled in by nginx (or the dev server) from env vars. */
@Serializable
data class AppConfigResponse(val stripePublishableKey: String)
