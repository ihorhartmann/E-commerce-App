package com.hartmann.e2e.support.auth

import com.hartmann.e2e.support.docker.DockerComposeStack

/** The users of docker/keycloak/realm-ecommerce.json. */
enum class TestUser(val username: String) {
    CLIENT1("client1"),
    CLIENT2("client2"),
    COURIER1("courier1"),
    COURIER2("courier2"),
    ADMIN("admin"),
    ;

    /** The clients and couriers are the public demo accounts (README); only the admin's password is a secret. */
    val password: String
        get() = when (this) {
            CLIENT1, CLIENT2, COURIER1, COURIER2 -> "demo"
            ADMIN -> DockerComposeStack.requiredEnv(ADMIN_PASSWORD_ENV)
        }
}

const val ADMIN_PASSWORD_ENV = "APP_ADMIN_PASSWORD"
