package com.hartmann.core.auth.dto

import com.hartmann.core.auth.model.CurrentUser
import com.hartmann.core.auth.model.Role
import kotlinx.serialization.Serializable

@Serializable
data class CurrentUserResponse(
    val id: String,
    val username: String?,
    val email: String?,
    val name: String?,
    val roles: List<String>,
)

fun fromCurrentUserResponse(response: CurrentUserResponse): CurrentUser = CurrentUser(
    id = response.id,
    displayName = response.name ?: response.username ?: response.email ?: response.id,
    roles = response.roles.mapNotNull { Role.fromName(it) }.toSet(),
)
