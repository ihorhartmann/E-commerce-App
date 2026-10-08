package com.hartmann.frameworks.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ServiceTokenConfigTest {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authorizedClient(principalName: String, tokenValue: String): OAuth2AuthorizedClient {
        val registration = ClientRegistration.withRegistrationId(principalName)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .clientId(principalName)
            .tokenUri("http://keycloak/token")
            .build()
        val now = Instant.now()
        val token = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue, now, now.plusSeconds(300))
        return OAuth2AuthorizedClient(registration, principalName, token)
    }

    @Test
    fun `serviceTokenInterceptor - sends the service's own token, not the one of the user being served`() {
        val serviceName = "order-service"
        val tokenValue = "service-token"
        val path = "/api/v1/reservations"
        val authorizeRequest = slot<OAuth2AuthorizeRequest>()
        val manager = mockk<OAuth2AuthorizedClientManager>()
        every { manager.authorize(capture(authorizeRequest)) } returns authorizedClient(serviceName, tokenValue)
        // the call is made while a customer's request is being handled
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("client1", null, "ROLE_CLIENT")

        val builder = RestClient.builder().requestInterceptor(ServiceTokenConfig().serviceTokenInterceptor(manager))
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(path))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $tokenValue"))
            .andRespond(withSuccess())

        builder.build().post().uri(path).retrieve().toBodilessEntity()

        server.verify()
        verify(exactly = 1) { manager.authorize(any()) }
        assertThat(authorizeRequest.captured.clientRegistrationId).isEqualTo(serviceName)
        assertThat(authorizeRequest.captured.principal.name).isEqualTo(serviceName)
    }
}
