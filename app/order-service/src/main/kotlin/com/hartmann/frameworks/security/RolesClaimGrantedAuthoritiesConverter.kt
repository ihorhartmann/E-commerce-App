package com.hartmann.frameworks.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Turns the roles found at [rolesClaim] (a dot-separated path, e.g. `realm_access.roles` for Keycloak or
 * `cognito:groups` for Cognito) into `ROLE_*` authorities, so `hasRole(...)` rules work whichever IdP issued the token.
 */
class RolesClaimGrantedAuthoritiesConverter(rolesClaim: String) : Converter<Jwt, Collection<GrantedAuthority>> {
    private val rolesClaimPath = rolesClaim.split(".")

    override fun convert(jwt: Jwt): Collection<GrantedAuthority> {
        val roles = rolesClaimPath.fold<String, Any?>(jwt.claims) { node, key -> (node as? Map<*, *>)?.get(key) }
        return (roles as? Collection<*>).orEmpty().map { SimpleGrantedAuthority("$ROLE_PREFIX$it") }
    }

    private companion object {
        const val ROLE_PREFIX = "ROLE_"
    }
}
