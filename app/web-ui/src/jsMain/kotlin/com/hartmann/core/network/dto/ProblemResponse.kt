package com.hartmann.core.network.dto

import kotlinx.serialization.Serializable

/**
 * The RFC 9457 problem details every service answers an error with (`application/problem+json`) - only the members
 * the UI uses. [code] tells one failure from another; [title] is the code's public title, the same for every
 * occurrence; [detail], when present, says more about this one. All are optional, so a problem without them still parses.
 */
@Serializable
data class ProblemResponse(
    val code: String? = null,
    val title: String? = null,
    val detail: String? = null,
)
