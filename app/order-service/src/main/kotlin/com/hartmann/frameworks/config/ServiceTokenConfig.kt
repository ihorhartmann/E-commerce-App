package com.hartmann.frameworks.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor
import org.springframework.security.oauth2.core.AuthorizationGrantType

/**
 * This service's own access token for its synchronous calls to the inventory- and payment-service (OAuth2 client
 * credentials). They let the call through by the service's roles, not by the user whose request triggered it - so a
 * customer's token can't do the same, and the call works just as well where there is no user (a Kafka consumer, a
 * scheduled job).
 *
 * The token is fetched on the first call, cached until shortly before it expires and then fetched again; a 401 from
 * the callee drops it, so the next call gets a fresh one.
 */
@Configuration
class ServiceTokenConfig {
    companion object {
        const val SERVICE_TOKEN_INTERCEPTOR_BEAN_NAME = "serviceTokenInterceptor"

        /** Which client registration - how to get the token: token endpoint, client id and secret, grant. */
        private const val REGISTRATION_ID = "order-service"

        /** Whose token it is - the key it is cached under, never sent to Keycloak. */
        private const val SERVICE_PRINCIPAL_NAME = "order-service"

        /**
         * Required by AnonymousAuthenticationToken, but never checked: only AnonymousAuthenticationProvider does that,
         * and SERVICE_PRINCIPAL never goes through it.
         */
        private const val SERVICE_PRINCIPAL_KEY = "service-token"

        /**
         * Whom the cached token belongs to. Always this service - never the user whose request is being handled, or
         * every customer would get a token of their own (and a Kafka consumer, with no user at all, none). The
         * authority is required by the constructor and grants nothing - this never reaches the SecurityContext.
         */
        private val SERVICE_PRINCIPAL: Authentication = AnonymousAuthenticationToken(
            SERVICE_PRINCIPAL_KEY,
            SERVICE_PRINCIPAL_NAME,
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
        )
    }

    /**
     * Not the request-bound manager Spring would build for a web app: this one keeps the token in memory, independent
     * of any HTTP request, so it works from any thread.
     */
    @Bean
    fun serviceAuthorizedClientManager(
        @Value($$"${oidc.token-uri}") tokenUri: String,
        @Value($$"${oidc.client-id}") clientId: String,
        @Value($$"${oidc.client-secret}") clientSecret: String,
    ): OAuth2AuthorizedClientManager {
        val registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .tokenUri(tokenUri)
            .build()
        val registrations = InMemoryClientRegistrationRepository(registration)

        return AuthorizedClientServiceOAuth2AuthorizedClientManager(
            registrations,
            InMemoryOAuth2AuthorizedClientService(registrations),
        ).apply {
            setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build())
        }
    }

    /** Puts the service's token into every request of a RestClient it is added to. */
    @Bean(SERVICE_TOKEN_INTERCEPTOR_BEAN_NAME)
    fun serviceTokenInterceptor(
        serviceAuthorizedClientManager: OAuth2AuthorizedClientManager,
    ): ClientHttpRequestInterceptor =
        OAuth2ClientHttpRequestInterceptor(serviceAuthorizedClientManager).apply {
            setClientRegistrationIdResolver { REGISTRATION_ID }
            setPrincipalResolver { SERVICE_PRINCIPAL }
        }
}
