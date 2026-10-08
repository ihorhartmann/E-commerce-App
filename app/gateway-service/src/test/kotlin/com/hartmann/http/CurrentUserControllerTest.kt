package com.hartmann.http

import com.hartmann.config.OidcProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.time.Instant

@WebFluxTest(
    controllers = [CurrentUserController::class],
    properties = [
        "server.port=0",
        "oidc.issuer=http://issuer.test/realms/ecommerce",
        "oidc.internal-uri=http://internal.test/realms/ecommerce",
        "oidc.client-id=gateway",
        "oidc.client-secret=secret",
        "oidc.roles-claim=realm_access.roles",
    ],
)
@EnableConfigurationProperties(OidcProperties::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CurrentUserControllerTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var authorizedClientManager: ReactiveOAuth2AuthorizedClientManager

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    @Test
    fun `getCurrentUser - returns the user from the Bearer access token`() {
        val userId = "0192f1d2-7c4b-7000-8000-000000000001"
        val username = "client1"
        val email = "client1@example.com"
        val name = "Client One"
        val role = "CLIENT"

        webTestClient.mutateWith(mockJwt().jwt {
            it.subject(userId)
                .claim("preferred_username", username)
                .claim("email", email)
                .claim("name", name)
                .claim("realm_access", mapOf("roles" to listOf(role)))
        })
            .get().uri("/api/v1/me")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(userId)
            .jsonPath("$.username").isEqualTo(username)
            .jsonPath("$.email").isEqualTo(email)
            .jsonPath("$.name").isEqualTo(name)
            .jsonPath("$.roles.length()").isEqualTo(1)
            .jsonPath("$.roles[0]").isEqualTo(role)

        verifyNoInteractions(authorizedClientManager, jwtDecoder)
    }

    @Test
    fun `getCurrentUser - returns the user from the access token kept in the browser session`() {
        val userId = "0192f1d2-7c4b-7000-8000-000000000002"
        val username = "courier1"
        val role = "COURIER"
        val tokenValue = "session-access-token"
        val issuedAt = Instant.parse("2026-01-01T00:00:00Z")
        val expiresAt = Instant.parse("2026-01-01T00:05:00Z")
        val authorizedClient = OAuth2AuthorizedClient(
            clientRegistration(),
            username,
            OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue, issuedAt, expiresAt),
        )
        val accessToken = Jwt.withTokenValue(tokenValue)
            .header("alg", "RS256")
            .subject(userId)
            .claim("preferred_username", username)
            .claim("realm_access", mapOf("roles" to listOf(role)))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build()
        whenever(authorizedClientManager.authorize(any())).thenReturn(Mono.just(authorizedClient))
        whenever(jwtDecoder.decode(tokenValue)).thenReturn(Mono.just(accessToken))

        webTestClient.mutateWith(mockOidcLogin())
            .get().uri("/api/v1/me")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(userId)
            .jsonPath("$.username").isEqualTo(username)
            .jsonPath("$.roles.length()").isEqualTo(1)
            .jsonPath("$.roles[0]").isEqualTo(role)

        verify(authorizedClientManager, times(1)).authorize(any())
        verify(jwtDecoder, times(1)).decode(tokenValue)
    }

    @Test
    fun `getCurrentUser - returns no roles when the token has no roles claim`() {
        val userId = "0192f1d2-7c4b-7000-8000-000000000003"

        webTestClient.mutateWith(mockJwt().jwt { it.subject(userId) })
            .get().uri("/api/v1/me")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(userId)
            .jsonPath("$.roles.length()").isEqualTo(0)

        verifyNoInteractions(authorizedClientManager, jwtDecoder)
    }

    @Test
    fun `getCurrentUser - rejects an unauthenticated request`() {
        webTestClient
            .get().uri("/api/v1/me")
            .exchange()
            .expectStatus().isUnauthorized

        verifyNoInteractions(authorizedClientManager, jwtDecoder)
    }

    private fun clientRegistration(): ClientRegistration = ClientRegistration.withRegistrationId("keycloak")
        .clientId("gateway")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .authorizationUri("http://issuer.test/auth")
        .tokenUri("http://internal.test/token")
        .build()
}
