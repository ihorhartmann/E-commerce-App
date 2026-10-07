package com.hartmann.frameworks.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.security.oauth2.jwt.Jwt

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RolesClaimGrantedAuthoritiesConverterTest {

    private fun jwtWithClaim(name: String, value: Any): Jwt =
        Jwt.withTokenValue("token").header("alg", "RS256").subject("user").claim(name, value).build()

    @Test
    fun `convert - maps the roles at a nested claim path to ROLE_ authorities`() {
        val converter = RolesClaimGrantedAuthoritiesConverter("realm_access.roles")
        val jwt = jwtWithClaim("realm_access", mapOf("roles" to listOf("CLIENT", "ADMIN")))

        val actual = converter.convert(jwt).map { it.authority }

        assertThat(actual).containsExactly("ROLE_CLIENT", "ROLE_ADMIN")
    }

    @Test
    fun `convert - maps the roles at a top-level claim`() {
        val converter = RolesClaimGrantedAuthoritiesConverter("cognito:groups")
        val jwt = jwtWithClaim("cognito:groups", listOf("COURIER"))

        val actual = converter.convert(jwt).map { it.authority }

        assertThat(actual).containsExactly("ROLE_COURIER")
    }

    @Test
    fun `convert - returns no authorities when the claim path is missing`() {
        val converter = RolesClaimGrantedAuthoritiesConverter("realm_access.roles")
        val jwt = jwtWithClaim("resource_access", mapOf("roles" to listOf("CLIENT")))

        val actual = converter.convert(jwt)

        assertThat(actual).isEmpty()
    }

    @Test
    fun `convert - returns no authorities when the claim is not a list`() {
        val converter = RolesClaimGrantedAuthoritiesConverter("realm_access.roles")
        val jwt = jwtWithClaim("realm_access", mapOf("roles" to "CLIENT"))

        val actual = converter.convert(jwt)

        assertThat(actual).isEmpty()
    }
}
