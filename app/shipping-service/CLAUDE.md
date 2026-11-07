# 4. Shipping Service Architecture

**Architecture:** Hexagonal

**Language:** Go

**Technologies:** GORM, Gin, kafka-go

See the [root CLAUDE.md](../../CLAUDE.md#go-specific-conventions) for Go-specific code conventions.

## Layers and Packages

### Domain

Contains domain objects that represent business concepts and rules.

This layer must not depend on any other layer.

#### Package structure

```text
domain/
├── model/
└── value/
```

##### domain/model

Domain models with their own identity.

Examples:

* `Product`
* `Order`

A private type that holds part of one entity's state (e.g. `shipmentState` of `Shipment`) lives here, next to that
entity, not in `domain/value` - that one holds standalone, reusable value objects such as `Address`. Such a type is
part of the entity, not a value object, so it changes in place like the entity itself.

##### domain/value

Value objects used by domain models.

Value objects do not have their own identity.

Example:

* `Money`

---

### Application

Contains application business logic and ports that define how business logic interacts with external systems.

This layer depends only on the `Domain` layer.

#### Package structure

```text
application/
├── port/
│   ├── in/
│   └── out/
│       ├── repository/
│       ├── kafka/
│       ├── http/
│       └── ...
│
├── service/
└── ...
```

The package contains:

* Ports
* Services

##### application/ports/in

Inbound ports are interfaces that define how business logic is invoked by `Adapters` layer.

Inbound ports must be named like `EntityService`, for example `ShipmentService`.

##### application/ports/out

Outbound ports are interfaces that define how business logic interacts with external systems, such as:
databases, kafka, third-party services.

See the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for naming conventions.

##### application/services

Services contain business logic that operates on domain objects.

Classes in this package provide implementations of the inbound service port interfaces defined in `application/ports/in`
and must be named like `EntityServiceImpl`, for example `ShipmentServiceImpl`.

---

### Adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Application` and `Domain` layers.

#### Package structure

```text
adapter/
├── common/
│   ├── kafka/
│   ├── logging/
│   └── ...
│
├── in/
│   ├── http/
│   ├── kafka/
│   └── scheduled/
│
└── out/
    ├── kafka/
    ├── http/
    ├── repository/
    └── ...
```

##### adapter/common

Shared code used by both incoming and outgoing adapters.

Examples:

* Kafka message types
* Shared adapter utilities

Kafka producer and consumer interfaces do NOT live here, since neither is actually used by both directions:
`KafkaMessageProducer` lives in `adapter/out/kafka/common` (used only by outbound Kafka adapters) and
`KafkaMessageConsumer` lives in `adapter/in/kafka/common` (used only by inbound Kafka adapters).

##### adapter/out

Outbound adapters that implement interfaces defined in `application/ports/out`. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for naming conventions.

##### adapter/in

Inbound adapters that invoke application logic and serve as entry points to the service.

Examples:

* REST API handlers
* Kafka consumers

A controller never writes an error response itself: it hands the error to Gin with `_ = c.Error(err)`, as it is - see
[Error handling](#error-handling). Only an error that isn't ours yet gets a sentinel there, e.g.
`c.ShouldBindJSON`'s as `httperror.ErrMalformedRequestBody`.

Each controller must provide a `RegisterRoutes` function responsible for registering all controller routes. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for controller/consumer naming conventions.

`adapter/in/http/auth` holds authentication for the HTTP API: the `TokenVerifier` interface, `Principal` and
`AccessRule`; its JWT/JWKS-based implementation (`JWKSTokenVerifier`) lives in `infrastructure/jwks`, like the
kafka-go implementations of adapter interfaces. `middleware.AuthMiddleware` checks every request against the rule
table in `adapter/in/http/middleware/auth_access_rules.go` (anything not listed is denied) and must stay registered after
`HandleMiddlewareError` and before `IdempotencyMiddleware`, so a rejected request never claims its Idempotency-Key.
A new endpoint needs a rule there.

`adapter/in/http/idempotency` holds `HTTPIdempotencyService`, which decides whether a mutating HTTP request should
proceed, replay a cached response, or be rejected, for `middleware.IdempotencyMiddleware`. It composes
`application/port/out/idempotency.HTTPRequestStore` (a persistence port for idempotent_http_requests rows,
implemented by `adapter/out/idempotency/store`) but owns all of the actual decision logic itself - the store never
branches on what a conflict or a stale record means. `HTTPIdempotencyService` lives here, not in `Application`,
because its whole purpose is specific to the HTTP idempotency-key pattern (an `Adapters`-owned concern), not to
orchestrating domain behavior.

`adapter/in/scheduled` holds periodic sweeps (e.g. `HTTPRequestSweep`, `ProcessedKafkaMessageSweep`) that delete
expired idempotency rows. A sweep is a self-triggered entry point - it decides on its own schedule to invoke a
business port, the same role a Kafka consumer or HTTP handler plays for its own trigger - so it belongs in
`Adapters`, not `Infrastructure`, even though nothing here is framework-specific glue.

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* GORM
* Database migrations
* kafka-go
* Environment configuration
* Application configuration

This layer may depend only on the `Adapters` layer.

This layer contains the kafka-go-based implementations of the `KafkaMessageProducer`/`KafkaMessageConsumer`
interfaces declared in `adapter/out/kafka/common`/`adapter/in/kafka/common`.

These implementations do not contain business-specific logic and are therefore implemented at the infrastructure level.

Kafka producers and consumers that contain business-specific behavior belong to the `Adapters` layer.

`infrastructure/telemetry` installs the global OpenTelemetry tracer provider and propagator (`InitTracing`, called
first in `main.go`) that the instrumentation uses: `otelgin` for HTTP, `otelgorm` for SQL, and the kafka-go consumer's
and producer's own spans (`infrastructure/kafkago/kafkago_tracing.go`). A trace starts only at an entry point - an
incoming request or message; any other span without a parent (the outbox relay's polling query, a sweep) is dropped.
The outbox keeps a message's trace: `adapter/out/outbox` stores the current span's `traceparent` with it, and
`kafkaGoMessageProducer.SendBatch` sends it in a producer span that is a child of that one. Pass the request's or
message's `ctx` down to the repositories (`c.Request.Context()` in a controller), so their SQL spans join its trace.

`telemetry.InitMetrics`, right after it, installs the global meter provider (and the Go runtime's metrics): the
connection pool's metrics come from `otelgorm`, the requests' from `middleware.MetricsMiddleware` (registered first,
otelgin's own metrics are off), the messages' from the kafka-go consumer (`infrastructure/kafkago/kafkago_metrics.go`),
the outbox relay's from `adapter/in/scheduled/outbox_metrics.go`, the error codes from `httperror.Write` - see the root
CLAUDE.md for the names. `adapter/in/http.HealthController` answers the probes; its routes are registered before any
middleware.

`telemetry.NewLogHandler` is the `slog` handler `main.go` installs: JSON lines on stdout, with the trace and span of the
`ctx` a line is logged with, and the attrs that `ctx` carries from `adapter/common/logging.WithAttrs` - the kafka-go
consumer gives the handler's `ctx` the message's ids that way.

## Error handling

Every error is a `domain/apperror.Error`: a sentinel declared once, as a package-level variable in the `errors.go`
next to the code that returns it, or an occurrence of it made by the sentinel's `With*` methods.

```go
var ErrNotAssignedCourier = apperror.New("NOT_ASSIGNED_COURIER", apperror.CategoryInvalid, "The shipment is not assigned to this courier")

return ErrNotAssignedCourier.
	WithAttrs(slog.Uint64("shipmentID", shipment.Id()), slog.String("courierID", courierID.String()))
```

* The code (`SHIPPING.NOT_ASSIGNED_COURIER` - `New` adds the service prefix) and the title are public and the same for
  every occurrence; keep codes specific (`SHIPMENT_NOT_FOUND`, not `NOT_FOUND`). The category alone decides the HTTP
  status, whether a Kafka message is retried or dead-lettered at once (permanent categories: invalid, not found,
  conflict), and whether the idempotency middleware caches the response or releases the key.
* The internal message and the attrs never leave the service; keep the internal message constant and put every value
  in the attrs. The external message (`WithExternalMessage`) is the only per-occurrence text a caller sees.
* An error gets its sentinel where it first enters this service's code: a domain rule, a Postgres repository turning
  a gorm error into its `ErrDatabaseFailure.WithCause(err)`, a controller turning a `ShouldBindJSON` error into
  `httperror.ErrMalformedRequestBody.WithCause(err)`. An error that already is one of ours is returned as it is -
  giving it to `WithCause` of another sentinel replaces its code; do that only on purpose (e.g. `StartDelivery`
  reporting a concurrent update as `ErrAlreadyPickedUp`).
* A sentinel lives where the root CLAUDE.md puts errors: next to a port only if the port's callers branch on it
  (`repository.ErrShipmentNotFound`, `repository.ErrConcurrentUpdate`), otherwise next to the adapter that returns it
  (`adapter/out/repository.ErrDatabaseFailure`, `adapter/out/idempotency/store.ErrStoreFailure`,
  `adapter/out/repository/transaction.ErrTransactionFailure`, `adapter/out/outbox.ErrOutboxFailure`).
* Stored data that breaks a domain rule is corrupted, not an invalid request: `NewExistingShipment` and the entity
  mapper report it as `ErrInconsistentShipment` (internal).
* A rule only this service's own code can break - a call no caller should make, e.g. a transition into a state no
  shipment can be in, which the service's own checks should have prevented - is a bug, neither corrupted data nor a
  request to reject: `ErrShipmentInvariantViolated` (internal), with an internal message saying which rule. Keep it
  apart from `ErrInconsistentShipment`, so the code tells whether to look at the data or at the code.
* Keep errors in variables and results of type `error`, never `*apperror.Error`: a nil `*Error` returned as an `error`
  is not a nil error.
* Log an error once, where it is handled - `httperror.Write`, the Kafka consumer, a sweep, the outbox relay - with
  `apperror.LogAttr(err)`, not `"error", err`.

HTTP error responses are RFC 9457 problem details, written only by `httperror.Write`. It is called at two points of
the middleware chain, in this order in `main.go` (see `adapter/in/http/middleware/error_handler_middleware.go`):
`MetricsMiddleware` -> `otelgin` -> `HandleMiddlewareError` -> `Recover` -> `LoggingMiddleware` -> `AuthMiddleware` ->
`IdempotencyMiddleware` -> `HandleControllerError` -> routes - the same as inventory, with `HandleMiddlewareError`
where Fiber has its `ErrorHandler`. Gin handlers return no error: they add it with `c.Error`, and both error handlers
are ordinary middleware that write the last one after `c.Next()`. `HandleControllerError` writes a controller's error
right after it, so `IdempotencyMiddleware` sees the final response; `HandleMiddlewareError` writes the errors of the
middleware below it. `MetricsMiddleware` and `otelgin` stay above both: they read the status from the written
response - unlike inventory's, which gets the error back from `c.Next()` - so every error must be written by the time
they get back. The router is built with `gin.New()`, not `gin.Default()`, whose own logger and recovery would
bypass this chain; unknown routes and methods go through it too (`NoRoute`/`NoMethod`). A status that differs from
the category's default (401, 403, 405, 422) is listed in `httperror.StatusOf`.

A panic is recovered - in a controller by `HandleControllerError`, in a middleware by `Recover`, in a Kafka handler by
the consumer, in a sweep or the outbox relay by itself - and becomes an `apperror.ErrInternal` occurrence via
`apperror.FromPanic`.
