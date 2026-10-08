package com.hartmann.e2e.support.auth

import com.hartmann.e2e.support.docker.DockerComposeStack

/** The test users of docker/keycloak/realm-ecommerce.json; their passwords come from the env, like every other secret. */
enum class TestUser(val username: String, val passwordEnv: String) {
    CLIENT1("client1", "E2E_CLIENT1_PASSWORD"),
    CLIENT2("client2", "E2E_CLIENT2_PASSWORD"),
    COURIER1("courier1", "E2E_COURIER1_PASSWORD"),
    COURIER2("courier2", "E2E_COURIER2_PASSWORD"),
    ADMIN("admin", "E2E_ADMIN_PASSWORD"),
    ;

    val password: String
        get() = DockerComposeStack.requiredEnv(passwordEnv)
}
