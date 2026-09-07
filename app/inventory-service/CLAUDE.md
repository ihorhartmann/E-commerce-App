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
│   │   └── kafka/
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
└── repository/
    └── transaction/
```

Outbound adapter implementations that implement the ports declared in `application/port/out` follow the naming
conventions in the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming).

`env`, `gorm`, and `kafkago` contain framework- and driver-level code (environment configuration, GORM setup and
migrations, the raw kafka-go client wrapper) that contains no business-specific logic.

---

### Interfaces

Contains entry points to the service and invoke the business logic in the `Application` layer.

This layer may depend on the `Application` and `Domain` layers.

HTTP error handling must be declared in an `errors.go` file located next to the controller where it is used.

Each controller must provide a `RegisterRoutes` function responsible for registering all controller routes. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for controller/consumer naming conventions.

#### Package structure

Packages are grouped by transport or technology type.

```text
interfaces/
├── http/
│   ├── dto/
│   ├── errors/
│   ├── idempotency/
│   └── middleware/
└── kafka/
    ├── common/
    ├── dto/
    └── kafkago/
```

`interfaces/kafka/kafkago` holds the kafka-go-based implementation of `interfaces/kafka/common.KafkaMessageConsumer` -
it lives here, not in `infrastructure/kafkago`, because that interface belongs to the `Interfaces` layer.

`interfaces/http/idempotency` holds `HTTPIdempotencyService`, which decides whether a mutating HTTP request should
proceed, replay a cached response, or be rejected, for `middleware.IdempotencyMiddleware`. It composes
`application/port/out/idempotency.HTTPRequestStore` (a persistence port for idempotent_http_requests
rows, implemented by `infrastructure/idempotency`) but owns all of the actual decision logic itself - the store never
branches on what a conflict or a stale record means. `HTTPIdempotencyService` lives here, not in `Application`,
because its whole purpose is specific to the HTTP idempotency-key pattern (an `Interfaces`-owned concern, same
reasoning as `interfaces/kafka/kafkago` above), not to orchestrating domain behavior.
