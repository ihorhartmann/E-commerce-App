package com.hartmann.core.network.dto

import kotlinx.serialization.Serializable

/**
 * The RFC 9457 problem details every service answers an error with (`application/problem+json`) - only the members
 * the UI shows. [title] is the error code's public title, the same for every occurrence; [detail], when present, says
 * more about this one. Both are optional, so a problem without them still parses.
 */
@Serializable
data class ProblemResponse(
    val title: String? = null,
    val detail: String? = null,
)
