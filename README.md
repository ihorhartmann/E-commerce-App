# E-commerce-App

Four business services (inventory, order, payment, shipping) behind a Spring Cloud Gateway, with Keycloak as the
identity provider, and a web UI (Kotlin, Compose HTML) for customers, couriers and admins. See [CLAUDE.md](CLAUDE.md)
for the architecture.

* Order checkout saga architecture (REST/Kafka, compensations): [docs/order_saga.md](docs/order_saga.md)

## Running locally

1. Copy `example.env` to `.env` and put your Stripe test-mode keys into `STRIPE_SECRET_KEY` and
   `STRIPE_PUBLISHABLE_KEY`.
2. Forward Stripe's webhooks to the gateway and put the `whsec_...` secret it prints into `STRIPE_WEBHOOK_SECRET`:

   ```bash
   stripe listen --all-snapshot --forward-to localhost:8080/api/v1/webhooks/stripe
   ```

3. Start everything:

   ```bash
   docker compose up -d --build
   ```

| What | URL |
|---|---|
| Web UI - catalog, cart and checkout, orders, shipments | http://localhost:3000 |
| Gateway - the entry point for clients | http://localhost:8080 |
| Keycloak admin console (`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`) | http://localhost:8090/admin |
| Kafka UI | http://localhost:9098 |
| Services directly (debugging, e2e tests) | http://localhost:3001 (inventory) ... 3004 (shipping) |

Keycloak imports the realm (`docker/keycloak/realm-ecommerce.json`) only when it doesn't exist yet. After changing
the realm file, recreate the database volume: `docker compose down -v`. Its clients `e2e-tests` and `postman` are off
when `KC_TEST_CLIENTS_ENABLED` is `false`, as in production.

The passwords and secrets of `.env` reach the containers as Docker secrets - files in `/run/secrets`, never
environment variables: Compose takes them from `.env` here, from the files in `secrets/` in production.

The web UI pays through Stripe test mode: use the card `4242 4242 4242 4242` with any future expiry date and any CVC.
An order moves on only once Stripe's webhook reaches the gateway, so keep `stripe listen` (step 2) running.

## Demo and test users

The same accounts locally and in production, where anyone may use them: the clients and couriers share the password
`demo`, published here. They can't change their account (password, email, two-factor login): they have the roles
below and nothing else. The realm's password policy (8 characters at least, neither the username nor the email) applies
to everyone else, so the realm file holds the demo password as a hash.

| User | Password | Role | Can |
|---|---|---|---|
| `client1`, `client2` | `demo` | `CLIENT` | place, read and cancel own orders (with their shipment) |
| `courier1`, `courier2` | `demo` | `COURIER` | list available shipments and pick one up (its street shows only then), then deliver / lose / cancel (goods couldn't be handed over) / return it |
| `admin` | `APP_ADMIN_PASSWORD` (`.env`; generated in production, for the owner only) | `ADMIN` | create and update products, read payments and refunds |

Self-registration is open, and a new user gets the `CLIENT` role. The `order-service` client has its own service
account (roles `INVENTORY_RESERVE`, `PAYMENT_SUBMIT`) for its calls to the inventory- and payment-service.

## Getting a token

**Browser:** open http://localhost:8080/oauth2/authorization/keycloak, log in, then
http://localhost:8080/api/v1/me shows who you are and your roles. The gateway keeps the tokens in its session; the
browser only holds an `HttpOnly` session cookie.

**curl** - password grant through the `e2e-tests` client (dev/test only):

```bash
curl -s -X POST http://localhost:8090/realms/ecommerce/protocol/openid-connect/token -d grant_type=password -d client_id=e2e-tests -d username=client1 -d password=demo
```

Send the `access_token` from the response as `Authorization: Bearer <token>`, e.g. to
http://localhost:8080/api/v1/orders/1. It lives 5 minutes.

**Service token** - client credentials, as the order-service gets it:

```bash
curl -s -X POST http://localhost:8090/realms/ecommerce/protocol/openid-connect/token -u order-service:<ORDER_SERVICE_CLIENT_SECRET> -d grant_type=client_credentials
```

**Postman:** the collection authorizes with OAuth 2.0 Authorization Code (With PKCE) through the `postman` client -
Collection → Authorization → Get New Access Token. Its description explains the full flow and which user each
request needs.

## Developing the web UI

With the rest of the stack running in Docker, the Gradle dev server serves the UI straight from the sources and reloads
the page on every change; it forwards the gateway's paths just like the container's nginx. Stop the `web-ui` container
first (`docker compose stop web-ui`), since both use the same port:

```bash
cd app/web-ui
WEB_UI_PORT=3000 GATEWAY_URL=http://localhost:8080 STRIPE_PUBLISHABLE_KEY=pk_test_... ./gradlew jsBrowserDevelopmentRun --continuous
```

See [app/web-ui/CLAUDE.md](app/web-ui/CLAUDE.md) for its architecture.

## Tests

* Unit tests: `go test ./...` (inventory, shipping), `./gradlew test` (order, gateway), `mvn test` (payment).
* Web UI ViewModel tests: `./gradlew jsNodeTest` in `app/web-ui`.
* Go integration tests (Testcontainers): `go test -tags integration ./...`.
* E2E tests (`e2e_tests`, the whole stack via Testcontainers, real Stripe test mode):
    * stop the local stack first (`docker compose down`) - the tests start their own on the same ports and network;
    * the variables from `.env` must be in the environment, and the Stripe CLI must be installed;
    * on Windows with Docker Desktop also set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`;
    * `./gradlew test` in `e2e_tests`.
