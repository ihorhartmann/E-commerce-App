package com.hartmann.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.security.web.server.csrf.CsrfWebFilter
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

@Configuration
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        clientRegistrationRepository: ReactiveClientRegistrationRepository,
    ): SecurityWebFilterChain = http {
        authorizeExchange {
            authorize(HEALTH_PATH, permitAll)
            authorize(STRIPE_WEBHOOK_PATH, permitAll)
            // the catalog is public: browsing products needs no login, ordering them does
            authorize(pathMatchers(HttpMethod.GET, PRODUCTS_PATH), permitAll)
            authorize(anyExchange, authenticated)
        }
        // browser: Authorization Code + PKCE login, tokens kept in the server-side session, HttpOnly session cookie
        oauth2Login { }
        // Postman, e2e tests: their own Bearer access token
        oauth2ResourceServer { jwt { } }
        csrf {
            // readable by the SPA, which echoes it back in the X-XSRF-TOKEN header
            csrfTokenRepository = CookieServerCsrfTokenRepository.withHttpOnlyFalse()
                .apply { setCookieCustomizer { it.sameSite(SAME_SITE_LAX) } }
            // the raw cookie value is expected back, not the BREACH-masked one meant for server-rendered forms
            csrfTokenRequestHandler = ServerCsrfTokenRequestAttributeHandler()
            requireCsrfProtectionMatcher = csrfProtectedRequestMatcher()
        }
        exceptionHandling {
            // the SPA decides itself when to send the user to /oauth2/authorization/keycloak
            authenticationEntryPoint = HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)
        }
        logout {
            // ends the Keycloak session too, otherwise the next login would go through without a password
            logoutSuccessHandler = OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository)
                .apply { setPostLogoutRedirectUri("{baseUrl}/") }
        }
    }

    /**
     * Only a request authenticated by the session cookie - which the browser attaches on its own - can be forged
     * cross-site. A Bearer token is attached explicitly by the caller, and a request with no session at all (e.g. the
     * Stripe webhook, or an anonymous call that should get a plain 401) has nothing to forge.
     */
    private fun csrfProtectedRequestMatcher(): ServerWebExchangeMatcher {
        val sessionRequest = ServerWebExchangeMatcher { exchange ->
            matchResult(exchange.request.cookies.containsKey(SESSION_COOKIE))
        }
        val bearerRequest = ServerWebExchangeMatcher { exchange ->
            matchResult(exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION) != null)
        }
        return AndServerWebExchangeMatcher(
            CsrfWebFilter.DEFAULT_CSRF_MATCHER,
            sessionRequest,
            NegatedServerWebExchangeMatcher(bearerRequest),
        )
    }

    private fun matchResult(matches: Boolean): Mono<ServerWebExchangeMatcher.MatchResult> =
        if (matches) ServerWebExchangeMatcher.MatchResult.match() else ServerWebExchangeMatcher.MatchResult.notMatch()

    @Bean
    fun csrfCookieWebFilter(): WebFilter = WebFilter { exchange, chain ->
        val csrfToken = exchange.getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name) ?: Mono.empty()
        csrfToken.then(chain.filter(exchange))
    }

    @Bean
    fun clientRegistrationRepository(oidc: OidcProperties): ReactiveClientRegistrationRepository =
        InMemoryReactiveClientRegistrationRepository(
            ClientRegistration.withRegistrationId(KEYCLOAK_REGISTRATION_ID)
                .clientId(oidc.clientId)
                .clientSecret(oidc.clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL)
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .issuerUri(oidc.issuer)
                .authorizationUri(oidc.authorizationUri)
                .tokenUri(oidc.tokenUri)
                .jwkSetUri(oidc.jwkSetUri)
                .userInfoUri(oidc.userInfoUri)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .providerConfigurationMetadata(mapOf(END_SESSION_ENDPOINT to oidc.endSessionUri))
                .build()
        )

    @Bean
    fun jwtDecoder(oidc: OidcProperties): ReactiveJwtDecoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(oidc.jwkSetUri).build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer(oidc.issuer))
        }

    private companion object {
        const val KEYCLOAK_REGISTRATION_ID = "keycloak"

        const val HEALTH_PATH = "/health"
        const val STRIPE_WEBHOOK_PATH = "/api/v1/webhooks/stripe"
        const val PRODUCTS_PATH = "/api/v1/products/**"
        const val END_SESSION_ENDPOINT = "end_session_endpoint"

        // WebFlux's default session cookie name (CookieWebSessionIdResolver)
        const val SESSION_COOKIE = "SESSION"
        const val SAME_SITE_LAX = "Lax"
    }
}
