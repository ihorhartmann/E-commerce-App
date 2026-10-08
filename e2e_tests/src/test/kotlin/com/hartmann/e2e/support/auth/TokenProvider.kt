package com.hartmann.e2e.support.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.hartmann.e2e.support.docker.DockerComposeStack
import io.restassured.RestAssured
import io.restassured.specification.RequestSpecification
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Access tokens of the test users, from Keycloak's password grant (client `e2e-tests`). Each one is cached until shortly
 * before it expires and then fetched again, so a scenario that outlives a token (Stripe, the saga) never sends an
 * expired one.
 */
object TokenProvider {
    private const val CLIENT_ID = "e2e-tests"
    private val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
    private val objectMapper = ObjectMapper()

    private val tokens = ConcurrentHashMap<TestUser, CachedToken>()

    fun accessToken(user: TestUser): String =
        tokens.compute(user) { _, cached -> cached?.takeIf { it.isFresh() } ?: fetch(user) }!!.value

    /** The user's id in Keycloak - what the services take as the customer or courier id. */
    fun subject(user: TestUser): UUID {
        val payload = Base64.getUrlDecoder().decode(accessToken(user).split(".")[1])
        return UUID.fromString(objectMapper.readTree(payload)["sub"].asText())
    }

    private fun fetch(user: TestUser): CachedToken {
        val response = RestAssured.given()
            .formParam("grant_type", "password")
            .formParam("client_id", CLIENT_ID)
            .formParam("username", user.username)
            .formParam("password", user.password)
            .post(DockerComposeStack.keycloakTokenUrl)
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract()
        val expiresIn = Duration.ofSeconds(response.path<Int>("expires_in").toLong())
        return CachedToken(response.path("access_token"), Instant.now().plus(expiresIn).minus(EXPIRY_MARGIN))
    }

    private class CachedToken(val value: String, private val refreshAt: Instant) {
        fun isFresh(): Boolean = Instant.now().isBefore(refreshAt)
    }
}

/** A request sent with the user's access token. */
fun givenAs(user: TestUser): RequestSpecification =
    RestAssured.given().auth().oauth2(TokenProvider.accessToken(user))
