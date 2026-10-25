package com.hartmann.infrastructure.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Turns the roles found at the configured claim path (dot-separated, e.g. {@code realm_access.roles} for Keycloak or
 * {@code cognito:groups} for Cognito) into {@code ROLE_*} authorities, so {@code hasRole(...)} rules work whichever
 * IdP issued the token.
 */
public class RolesClaimGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final String ROLE_PREFIX = "ROLE_";

    private final List<String> rolesClaimPath;

    public RolesClaimGrantedAuthoritiesConverter(String rolesClaim) {
        this.rolesClaimPath = List.of(rolesClaim.split("\\."));
    }

    // Converter allows a null result; this one always returns a collection, empty when the token carries no roles
    @Override
    public @NonNull Collection<GrantedAuthority> convert(Jwt jwt) {
        Object node = jwt.getClaims();
        for (var key : rolesClaimPath) {
            node = node instanceof Map<?, ?> map ? map.get(key) : null;
        }
        if (!(node instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }
}
