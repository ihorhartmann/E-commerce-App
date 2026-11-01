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
config/        security, client registration, JWT decoder, OIDC properties, tracing
http/          controllers of the gateway itself (e.g. GET /api/v1/me), with their DTOs in http/dto; its error
               responses (GlobalExceptionHandler) and request logging (RequestLoggingFilter)
observation/   span name conventions: `{METHOD} {route}`, a routed request by its route's path pattern
```

Routes live in `application.yml`, not in code.

## Errors and logging

* `GlobalExceptionHandler` (an `ErrorWebExceptionHandler`) answers every request the gateway fails itself with an
  RFC 9457 ProblemDetail shaped like the services' (`type` `urn:ecommerce:error:GATEWAY.<code>`, `title`, `status`,
  `instance`, `code`, `retryable`, `Retry-After` when retryable), its codes in `GatewayError`. It is deliberately
  minimal - a fixed mapping, not the services' exception hierarchy:
    * a route that doesn't exist (404), a method not allowed (405);
    * a service that can't be reached (503) or doesn't answer within the `httpclient` timeouts in `application.yml`
      (504), logged with the route and the URL the request was sent to;
    * a request that isn't authenticated (401) or fails the CSRF check (403) - `SecurityConfig` hands Spring
      Security's entry point and access-denied handler to it;
    * anything else, e.g. a failure of `CurrentUserController`, as a 500.

  It logs each failure once: a 5xx as an error, anything else as a warning.
* A service that answers with an error itself is passed on as it is: the service logged it, and `RequestLoggingFilter`
  adds only a debug line with the status. Its `received`/`successfully handled http request` lines are debug too -
  every routed request is logged by its service and recorded in its trace anyway.
* `spring.reactor.context-propagation: auto` restores the request's trace into the MDC on every reactive step, so the
  gateway's log lines carry `traceId` and `spanId` like the services'.

## Configuration

* `OIDC_*` env vars are bound straight into `OidcProperties` (no `${...}` placeholders for them in `application.yml`):
  a placeholder whose env var is missing would be bound as a literal string, and the gateway would start
  misconfigured instead of failing.
* The Keycloak provider is declared explicitly (`SecurityConfig.clientRegistrationRepository`), not via `issuer-uri`
  discovery: the issuer is the public URL, unreachable from inside the container, so front-channel endpoints use
  `OIDC_ISSUER` and back-channel ones `OIDC_INTERNAL_URI`.
