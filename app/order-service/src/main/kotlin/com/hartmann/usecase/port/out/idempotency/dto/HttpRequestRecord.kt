package com.hartmann.usecase.port.out.idempotency.dto

import java.time.Instant

data class HttpRequestRecord(
    val id: String,
    val requestHash: String,
    val httpCode: Int?,
    val response: String?,
    val createdAt: Instant,
    val claimedAt: Instant,
    val completedAt: Instant?,
)
