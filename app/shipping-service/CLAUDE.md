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
│   └── ...
│
├── in/
│   ├── http/
│   └── kafka/
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

HTTP error handling must be declared in an `errors.go` file located next to the controller where it is used.

Each controller must provide a `RegisterRoutes` function responsible for registering all controller routes. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for controller/consumer naming conventions.

`adapter/in/http/idempotency` holds `HTTPIdempotencyService`, which decides whether a mutating HTTP request should
proceed, replay a cached response, or be rejected, for `middleware.IdempotencyMiddleware`. It composes
`application/port/out/idempotency.HTTPRequestStore` (a persistence port for idempotent_http_requests rows,
implemented by `adapter/out/idempotency/store`) but owns all of the actual decision logic itself - the store never
branches on what a conflict or a stale record means. `HTTPIdempotencyService` lives here, not in `Application`,
because its whole purpose is specific to the HTTP idempotency-key pattern (an `Adapters`-owned concern), not to
orchestrating domain behavior.

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
