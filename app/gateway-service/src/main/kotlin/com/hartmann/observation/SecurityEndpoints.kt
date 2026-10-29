package com.hartmann.observation

// The endpoints Spring Security serves itself, ahead of any route or controller, so no pattern names them: fixed paths,
// as few as these, can name a span as they are. Matched as exact method-and-path pairs - any other request without a
// pattern (an unknown path, a 404) stays named by its method alone, as its raw path could be anything.
private val SECURITY_ENDPOINTS = setOf(
    // starts the login: the PKCE challenge and the redirect to Keycloak
    "GET /oauth2/authorization/keycloak",
    // Keycloak redirects back here with the code, exchanged for the tokens of the new session
    "GET /login/oauth2/code/keycloak",
    // ends the session, and Keycloak's with it
    "POST /logout",
    // the login and logout pages Spring Security generates, as oauth2Login has no page of its own
    "GET /login",
    "GET /logout",
)

// `{METHOD} {path}` when the request is to one of Spring Security's own endpoints, null otherwise.
fun securityEndpointSpanName(method: String, path: String?): String? =
    "$method $path".takeIf { path != null && it in SECURITY_ENDPOINTS }
