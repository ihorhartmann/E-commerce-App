package com.hartmann.core.auth.model

data class CurrentUser(val id: String, val displayName: String, val roles: Set<Role>) {
    fun hasRole(role: Role): Boolean = role in roles
}

/** The realm roles the UI cares about; service roles and Keycloak's defaults are left out. */
enum class Role {
    CLIENT,
    COURIER,
    ADMIN;

    companion object {
        fun fromName(name: String): Role? = entries.firstOrNull { it.name == name }
    }
}
