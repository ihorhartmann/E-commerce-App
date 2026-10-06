# Gateway Service Architecture

**Architecture:** none (no domain - routing and security configuration only)

**Language:** Kotlin

**Technologies:** Spring Cloud Gateway (WebFlux), Spring Security OAuth2 Client + Resource Server

## Roles

* **API Gateway** for every client: routes `/api/v1/**` to the services, the single entry point.
* **OAuth2 token handler (BFF in the IETF sense)** for the browser UI: Authorization Code + PKCE login against
  Keycloak, tokens kept in the server-side session, an `HttpOnly` session cookie for the browser, CSRF protection,
  `TokenRelay` replaces the cookie with the session's access token (refreshed when it expires).
* Bearer clients (Postman, e2e tests) send their own access token, which is validated here and passed on as is.

Services validate the JWT themselves too and own all authorization decisions (roles, resource ownership) - the
gateway only checks that a request is authenticated.

## Packages

```text
config/   security, client registration, JWT decoder, OIDC properties
http/     controllers of the gateway itself (e.g. GET /api/v1/me), with their DTOs in http/dto
```

Routes live in `application.yml`, not in code.

## Configuration

* `OIDC_*` env vars are bound straight into `OidcProperties` (no `${...}` placeholders for them in `application.yml`):
  a placeholder whose env var is missing would be bound as a literal string, and the gateway would start
  misconfigured instead of failing.
* The Keycloak provider is declared explicitly (`SecurityConfig.clientRegistrationRepository`), not via `issuer-uri`
  discovery: the issuer is the public URL, unreachable from inside the container, so front-channel endpoints use
  `OIDC_ISSUER` and back-channel ones `OIDC_INTERNAL_URI`.
