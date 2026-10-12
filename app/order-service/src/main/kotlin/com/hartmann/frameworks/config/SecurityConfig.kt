package com.hartmann.frameworks.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * OAuth2 resource server: every request carries a Keycloak access token (relayed by the gateway or sent by a Bearer
 * client), validated here against the realm's public keys. This only decides who may call which endpoint by role -
 * whether the order actually belongs to the caller is decided by the use cases.
 */
@Configuration
class SecurityConfig {
    private companion object {
        const val ERROR_PATH = "/error"
        const val ORDERS_PATH = "/api/v1/orders"
        const val CLIENT_ROLE = "CLIENT"
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationConverter: JwtAuthenticationConverter,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize(ERROR_PATH, permitAll)
                authorize(HttpMethod.POST, ORDERS_PATH, hasRole(CLIENT_ROLE))
                authorize(HttpMethod.POST, "$ORDERS_PATH/*/cancel", hasRole(CLIENT_ROLE))
                authorize(HttpMethod.GET, "$ORDERS_PATH/**", hasRole(CLIENT_ROLE))
                authorize(anyRequest, denyAll)
            }
            oauth2ResourceServer {
                jwt { this.jwtAuthenticationConverter = jwtAuthenticationConverter }
            }
            // stateless Bearer-token API: no session, and no cookie a cross-site request could ride on
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
        }
        return http.build()
    }

    /**
     * Keys come from the internal JWKS endpoint, but the issuer is checked against the public realm URL - the one
     * Keycloak always writes into `iss`. The keys are fetched lazily and cached, so the startup doesn't depend on
     * Keycloak being up.
     */
    @Bean
    fun jwtDecoder(
        @Value($$"${oidc.jwks-uri}") jwksUri: String,
        @Value($$"${oidc.issuer}") issuer: String,
    ): JwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build().apply {
        setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer))
    }

    @Bean
    fun jwtAuthenticationConverter(@Value($$"${oidc.roles-claim}") rolesClaim: String): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(RolesClaimGrantedAuthoritiesConverter(rolesClaim))
        }
}
