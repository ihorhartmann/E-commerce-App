package com.hartmann.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RolesClaimGrantedAuthoritiesConverterTest {

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("user").claim(name, value).build();
    }

    @Test
    void convert_mapsTheRolesAtANestedClaimPathToRoleAuthorities() {
        var converter = new RolesClaimGrantedAuthoritiesConverter("realm_access.roles");
        var jwt = jwtWithClaim("realm_access", Map.of("roles", List.of("CLIENT", "ADMIN")));

        var actual = converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(actual).containsExactly("ROLE_CLIENT", "ROLE_ADMIN");
    }

    @Test
    void convert_mapsTheRolesAtATopLevelClaim() {
        var converter = new RolesClaimGrantedAuthoritiesConverter("cognito:groups");
        var jwt = jwtWithClaim("cognito:groups", List.of("COURIER"));

        var actual = converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(actual).containsExactly("ROLE_COURIER");
    }

    @Test
    void convert_returnsNoAuthoritiesWhenTheClaimPathIsMissing() {
        var converter = new RolesClaimGrantedAuthoritiesConverter("realm_access.roles");
        var jwt = jwtWithClaim("resource_access", Map.of("roles", List.of("CLIENT")));

        var actual = converter.convert(jwt);

        assertThat(actual).isEmpty();
    }

    @Test
    void convert_returnsNoAuthoritiesWhenTheClaimIsNotAList() {
        var converter = new RolesClaimGrantedAuthoritiesConverter("realm_access.roles");
        var jwt = jwtWithClaim("realm_access", Map.of("roles", "CLIENT"));

        var actual = converter.convert(jwt);

        assertThat(actual).isEmpty();
    }
}
