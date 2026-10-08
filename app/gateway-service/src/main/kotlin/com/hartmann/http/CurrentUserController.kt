package com.hartmann.http

import com.hartmann.config.OidcProperties
import com.hartmann.http.dto.CurrentUserResponse
import com.hartmann.http.dto.toCurrentUserResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1/me")
class CurrentUserController(
    private val authorizedClientManager: ReactiveOAuth2AuthorizedClientManager,
    private val jwtDecoder: ReactiveJwtDecoder,
    private val oidc: OidcProperties,
) {
    @GetMapping
    fun getCurrentUser(authentication: Authentication, exchange: ServerWebExchange): Mono<CurrentUserResponse> =
        accessToken(authentication, exchange).map { toCurrentUserResponse(it, oidc.rolesClaim) }

    private fun accessToken(authentication: Authentication, exchange: ServerWebExchange): Mono<Jwt> =
        when (authentication) {
            // Bearer request: the token was already validated by the resource server filter
            is JwtAuthenticationToken -> Mono.just(authentication.token)
            // browser session: the access token lives in the session, refreshed by the manager if it has expired
            is OAuth2AuthenticationToken -> authorizedClientManager
                .authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(authentication.authorizedClientRegistrationId)
                        .principal(authentication)
                        .attribute(ServerWebExchange::class.java.name, exchange)
                        .build()
                )
                .flatMap { jwtDecoder.decode(it.accessToken.tokenValue) }

            else -> Mono.error(IllegalStateException("Unsupported authentication type: ${authentication::class.simpleName}"))
        }
}
