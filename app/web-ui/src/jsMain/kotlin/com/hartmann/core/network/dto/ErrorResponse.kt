package com.hartmann.core.network.dto

import kotlinx.serialization.Serializable

/** The error body every service answers with: `{"error": "..."}` (order-service adds a `status`). */
@Serializable
data class ErrorResponse(val error: String)
