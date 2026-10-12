# E-commerce-App

Four business services (inventory, order, payment, shipping) behind a Spring Cloud Gateway, with Keycloak as the
identity provider. See [CLAUDE.md](CLAUDE.md) for the architecture.

* Order checkout saga architecture (REST/Kafka, compensations): [docs/order_saga.md](docs/order_saga.md)

## Running locally

1. Copy `example.env` to `.env` and put your Stripe test-mode keys into `STRIPE_SECRET_KEY` and
   `STRIPE_PUBLISHABLE_KEY`.
2. Forward Stripe's webhooks to the gateway and put the `whsec_...` secret it prints into `STRIPE_WEBHOOK_SECRET`:

   ```bash
   stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe
   ```

3. Start everything:

   ```bash
   docker compose up -d --build
   ```

| What | URL |
|---|---|
| Gateway - the entry point for clients | http://localhost:8080 |
| Keycloak admin console (`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`) | http://localhost:8090/admin |
| Kafka UI | http://localhost:9090 |
| Services directly (debugging, e2e tests) | http://localhost:3001 (inventory) ... 3004 (shipping) |

Keycloak imports the realm (`docker/keycloak/realm-ecommerce.json`) only when it doesn't exist yet. After changing
the realm file, recreate the database volume: `docker compose down -v`.

## Test users

| User | Password | Role | Can |
|---|---|---|---|
| `client1`, `client2` | same as the username | `CLIENT` | place, read and cancel own orders (with their shipment) |
| `courier1`, `courier2` | same as the username | `COURIER` | list available shipments and pick one up (its street shows only then), then deliver / lose / cancel (goods couldn't be handed over) / return it |
| `admin` | `admin` | `ADMIN` | create and update products, read payments and refunds |

Self-registration is open, and a new user gets the `CLIENT` role. The `order-service` client has its own service
account (roles `INVENTORY_RESERVE`, `PAYMENT_SUBMIT`) for its calls to the inventory- and payment-service.

## Getting a token

**Browser:** open http://localhost:8080/oauth2/authorization/keycloak, log in, then
http://localhost:8080/api/v1/me shows who you are and your roles. The gateway keeps the tokens in its session; the
browser only holds an `HttpOnly` session cookie.

**curl** - password grant through the `e2e-tests` client (dev/test only):

```bash
curl -s -X POST http://localhost:8090/realms/ecommerce/protocol/openid-connect/token -d grant_type=password -d client_id=e2e-tests -d username=client1 -d password=client1
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

## Tests

* Unit tests: `go test ./...` (inventory, shipping), `./gradlew test` (order, gateway), `mvn test` (payment).
* Go integration tests (Testcontainers): `go test -tags integration ./...`.
* E2E tests (`e2e_tests`, the whole stack via Testcontainers, real Stripe test mode):
    * stop the local stack first (`docker compose down`) - the tests start their own on the same ports and network;
    * the variables from `.env` must be in the environment, and the Stripe CLI must be installed;
    * on Windows with Docker Desktop also set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`;
    * `./gradlew test` in `e2e_tests`.
