# Inventory Service Architecture

**Architecture:** Onion

**Language:** Go

**Technologies:** GORM, Fiber, kafka-go

See the [root CLAUDE.md](../../CLAUDE.md#go-specific-conventions) for Go-specific code conventions.

## Layers and Packages

### Domain

Contains domain objects that represent business concepts and rules.

This layer must not depend on any other layer.

#### Package structure

```text
domain/
├── model/
└── values/
```

##### domain/model

Domain models with their own identity.

Examples:

* `Product`
* `StockReservation`

##### domain/values

Value objects used by domain models.

Value objects do not have their own identity.

Example:

* `Money`

---

### Application

Contains application business logic and ports that define how business logic interacts with external systems.

This layer depends only on the `Domain` layer.

Errors in this layer must be declared in an `errors.go` file located next to the file where they are used.

#### Package structure

```text
application/
├── port/
│   ├── common/
│   │   ├── kafka/
│   │   └── logging/
│   ├── in/
│   └── out/
│       ├── kafka/
│       │   └── common/
│       └── repository/
└── ...
```

##### application/port/in

Inbound ports are interfaces that define how business logic is invoked by the `Interfaces` layer.

Inbound ports must be named like `EntityService`, for example `ProductService` or `ReservationService`.

##### application/port/out

Outbound ports are interfaces that define how business logic interacts with external systems, such as:
databases, kafka, third-party services.

See the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for naming conventions. Ports declared here are
implemented exclusively by outbound adapters in the `Infrastructure` layer.

##### application/port/common

Code shared between an `Infrastructure` adapter and an `Interfaces` adapter (not limited to constants - e.g. Kafka topic
names, header names, message-type names, or other shared types and utilities) must not live inside either layer's own
package, since `Infrastructure` and `Interfaces` are siblings and neither may depend on the other. Put it in a dedicated
package under `application/port/common` instead, since both layers are already allowed to depend on `Application`.

##### Service implementations

Classes implementing the inbound service ports live directly in the `application` package, named `EntityServiceImpl`,
for example `ProductServiceImpl` or `ReservationServiceImpl`.

---

### Infrastructure

Contains implementations of ports from the `Application` layer that integrate the application core with external systems
and infrastructure. Also includes framework-specific code, drivers, and related configuration.

This layer may depend on the `Application` and `Domain` layers.

#### Package structure

Packages are grouped by technical responsibility or external technology integration.

```text
infrastructure/
├── env/
├── gorm/
│   ├── migration/
│   └── transaction/
├── kafka/
│   └── common/
├── kafkago/
├── repository/
│   └── transaction/
└── telemetry/
```

Outbound adapter implementations that implement the ports declared in `application/port/out` follow the naming
conventions in the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming).

`env`, `gorm`, and `kafkago` contain framework- and driver-level code (environment configuration, GORM setup and
migrations, the raw kafka-go client wrapper) that contains no business-specific logic.

`telemetry` installs the global OpenTelemetry tracer provider and propagator (`InitTracing`, called first in `main.go`)
that the instrumentation uses: `otelfiber` for HTTP, `otelgorm` for SQL, and the Kafka consumer's own span per message
(`interfaces/kafka/kafkago/kafkago_tracing.go`). A trace starts only at an entry point - an incoming request or
message; any other span without a parent (a sweep's query) is dropped. Pass the request's or message's `ctx` down to
the repositories (`c.UserContext()` in a controller, not `c.Context()`), so their SQL spans join its trace.

`telemetry.InitMetrics`, right after it, installs the global meter provider (and the Go runtime's metrics): the
connection pool's metrics come from `otelgorm`, the requests' from `middleware.MetricsMiddleware` (registered first,
otelfiber's own metrics are off), the messages' from the consumer (`interfaces/kafka/kafkago/kafkago_metrics.go`), the
error codes from `httperror.Write` - see the root CLAUDE.md for the names. `interfaces/http.HealthController` answers the
probes; its routes are registered before any middleware.

`telemetry.NewLogHandler` is the `slog` handler `main.go` installs: JSON lines on stdout, with the trace and span of the
`ctx` a line is logged with, and the attrs that `ctx` carries from `application/port/common/logging.WithAttrs` - the
Kafka consumer gives the handler's `ctx` the message's ids that way - and the saga's correlation id an HTTP request
brought in its `X-Correlation-Id` header (`middleware.LoggingMiddleware`, `logging.WithCorrelationID`). `logging` lives
in `application/port/common`, as both the consumer and the middleware (`Interfaces`) and the handler
(`Infrastructure`) use it.

---

### Interfaces

Contains entry points to the service and invoke the business logic in the `Application` layer.

This layer may depend on the `Application` and `Domain` layers.

A controller returns an error as it is - it never maps it to a status; see [Error handling](#error-handling). Only an
error that isn't ours yet gets a sentinel there, e.g. `c.BodyParser`'s as `httperror.ErrMalformedRequestBody`.

Each controller must provide a `RegisterRoutes` function responsible for registering all controller routes. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for controller/consumer naming conventions.

#### Package structure

Packages are grouped by transport or technology type.

```text
interfaces/
├── http/
│   ├── dto/
│   ├── httperror/
│   ├── idempotency/
│   └── middleware/
├── kafka/
│   ├── common/
│   ├── dto/
│   └── kafkago/
└── scheduled/
```

`interfaces/kafka/kafkago` holds the kafka-go-based implementation of `interfaces/kafka/common.KafkaMessageConsumer` -
it lives here, not in `infrastructure/kafkago`, because that interface belongs to the `Interfaces` layer.

`interfaces/scheduled` holds periodic sweeps (e.g. `HTTPRequestSweep`, `ProcessedKafkaMessageSweep`) that delete
expired idempotency rows. A sweep is a self-triggered entry point - it decides on its own schedule to invoke a
business port, the same role a Kafka consumer or HTTP handler plays for its own trigger - so it belongs in
`Interfaces`, not `Infrastructure`, even though nothing here is framework-specific glue.

`interfaces/http/auth` holds authentication for the HTTP API: the `TokenVerifier` port, `Principal` and `AccessRule`;
its JWT/JWKS-based implementation (`JWKSTokenVerifier`) lives in `interfaces/http/auth/jwks` - in `Interfaces`, not
`Infrastructure`, for the same reason as `interfaces/kafka/kafkago`. `middleware.AuthMiddleware` checks every request
against the rule table in `interfaces/http/middleware/auth_access_rules.go` (anything not listed is denied) and must stay registered
before `IdempotencyMiddleware`, so a rejected request never claims its Idempotency-Key. A new endpoint needs a rule
there.

`interfaces/http/idempotency` holds `HTTPIdempotencyService`, which decides whether a mutating HTTP request should
proceed, replay a cached response, or be rejected, for `middleware.IdempotencyMiddleware`. It composes
`application/port/out/idempotency.HTTPRequestStore` (a persistence port for idempotent_http_requests
rows, implemented by `infrastructure/idempotency`) but owns all of the actual decision logic itself - the store never
branches on what a conflict or a stale record means. `HTTPIdempotencyService` lives here, not in `Application`,
because its whole purpose is specific to the HTTP idempotency-key pattern (an `Interfaces`-owned concern, same
reasoning as `interfaces/kafka/kafkago` above), not to orchestrating domain behavior.

## Error handling

Every error is a `domain/apperror.Error`: a sentinel declared once, as a package-level variable in the `errors.go`
next to the code that returns it, or an occurrence of it made by the sentinel's `With*` methods.

```go
var ErrInsufficientStock = apperror.New("INSUFFICIENT_STOCK", apperror.CategoryConflict, "Insufficient stock")

return ErrInsufficientStock.
	WithInternalMessage("cannot reserve product stock").
	WithAttrs(slog.Uint64("productID", id), slog.Uint64("available", available))
```

* The code (`INVENTORY.INSUFFICIENT_STOCK` - `New` adds the service prefix) and the title are public and the same for
  every occurrence; keep codes specific (`PRODUCT_NOT_FOUND`, not `NOT_FOUND`). The category alone decides the HTTP
  status, whether a Kafka message is retried or dead-lettered at once (permanent categories: invalid, not found,
  conflict), and whether the idempotency middleware caches the response or releases the key.
* The internal message and the attrs never leave the service; keep the internal message constant and put every value
  in the attrs. The external message (`WithExternalMessage`) is the only per-occurrence text a caller sees.
* An error gets its sentinel where it first enters this service's code: a domain rule, a Postgres repository turning
  a gorm error into its `ErrDatabaseFailure.WithCause(err)`, a controller turning a `BodyParser` error into
  `httperror.ErrMalformedRequestBody.WithCause(err)`. An error that already is one of ours is returned as it is -
  giving it to `WithCause` of another sentinel replaces its code; do that only on purpose.
* A sentinel lives where the root CLAUDE.md puts errors: next to a port only if the port's callers branch on it
  (`repository.ErrProductNotFound`, `repository.ErrConcurrentUpdate`), otherwise next to the adapter that returns it
  (`infrastructure/repository.ErrDatabaseFailure`, `infrastructure/idempotency/store.ErrStoreFailure`,
  `infrastructure/gorm/transaction.ErrTransactionFailure`).
* Stored data that breaks a domain rule is corrupted, not an invalid request: the `NewExisting*` constructors and the
  entity mappers report it as `ErrInconsistentProduct`/`ErrInconsistentReservation` (internal).
* A rule only this service's own code can break - a call no caller should make, e.g. applying another product's
  reservation to a product - is a bug, neither corrupted data nor a request to reject: `ErrProductInvariantViolated`
  (internal), with an internal message saying which rule. Keep it apart from `ErrInconsistent*`, so the code tells
  whether to look at the data or at the code.
* Keep errors in variables and results of type `error`, never `*apperror.Error`: a nil `*Error` returned as an `error`
  is not a nil error.
* Log an error once, where it is handled - `httperror.Write`, the Kafka consumer, a sweep - with
  `apperror.LogAttr(err)`, not `"error", err`.

HTTP error responses are RFC 9457 problem details, written only by `httperror.Write`. It is called at two points of
the middleware chain, in this order in `main.go` (see `interfaces/http/middleware/error_handler_middleware.go`):
`Recover` -> `LoggingMiddleware` -> `AuthMiddleware` -> `IdempotencyMiddleware` -> `HandleControllerError` -> routes,
with `HandleMiddlewareError` as Fiber's `ErrorHandler`. `HandleControllerError` writes a controller's error right after
it, so `IdempotencyMiddleware` sees the final response; `HandleMiddlewareError` writes the errors of the middleware
above it. A status that differs from the category's default (401, 403, 405, 413, 422) is listed in
`httperror.StatusOf`.

A panic is recovered - in a controller by `HandleControllerError`, in a middleware by `Recover`, in a Kafka handler by
the consumer, in a sweep by the sweep itself - and becomes an `apperror.ErrInternal` occurrence via
`apperror.FromPanic`.
