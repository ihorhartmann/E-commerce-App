package com.hartmann.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The one place that knows Keycloak's endpoint layout. Front-channel endpoints (the browser is redirected to them)
 * are built on the public [issuer]; back-channel ones (called by the gateway itself) on [internalUri].
 */
@ConfigurationProperties("oidc")
data class OidcProperties(
    val issuer: String,
    val internalUri: String,
    val clientId: String,
    val clientSecret: String,
    val rolesClaim: String,
) {
    val authorizationUri: String get() = "$issuer$OPENID_CONNECT_PATH/auth"
    val endSessionUri: String get() = "$issuer$OPENID_CONNECT_PATH/logout"
    val tokenUri: String get() = "$internalUri$OPENID_CONNECT_PATH/token"
    val jwkSetUri: String get() = "$internalUri$OPENID_CONNECT_PATH/certs"
    val userInfoUri: String get() = "$internalUri$OPENID_CONNECT_PATH/userinfo"

    private companion object {
        const val OPENID_CONNECT_PATH = "/protocol/openid-connect"
    }
}
