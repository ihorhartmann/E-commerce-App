package com.hartmann.http.dto

import org.springframework.security.oauth2.jwt.Jwt

data class CurrentUserResponse(
    val id: String,
    val username: String?,
    val email: String?,
    val name: String?,
    val roles: List<String>,
)

/**
 * @param rolesClaim dot-separated path to the roles array inside the token, e.g. `realm_access.roles` (Keycloak)
 */
fun toCurrentUserResponse(accessToken: Jwt, rolesClaim: String): CurrentUserResponse = CurrentUserResponse(
    id = requireNotNull(accessToken.subject) { "Access token has no sub claim" },
    username = accessToken.getClaimAsString(PREFERRED_USERNAME_CLAIM),
    email = accessToken.getClaimAsString(EMAIL_CLAIM),
    name = accessToken.getClaimAsString(NAME_CLAIM),
    roles = rolesFromClaim(accessToken.claims, rolesClaim),
)

private fun rolesFromClaim(claims: Map<String, Any>, rolesClaim: String): List<String> {
    val roles = rolesClaim.split(".").fold<String, Any?>(claims) { node, key -> (node as? Map<*, *>)?.get(key) }
    return (roles as? Collection<*>)?.map { it.toString() } ?: emptyList()
}

private const val PREFERRED_USERNAME_CLAIM = "preferred_username"
private const val EMAIL_CLAIM = "email"
private const val NAME_CLAIM = "name"
