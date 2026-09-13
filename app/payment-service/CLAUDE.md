# Payment Service Architecture

**Architecture:** Hexagonal

**Language:** Java

**Technologies:** SpringBoot, jOOQ

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

##### domain.model

Domain models with their own identity.

Examples:

* `Product`
* `Order`

##### domain.value

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

* Services
* Ports

##### application.port.in

Inbound ports are interfaces that define how business logic is invoked by `Adapters` layer.

Inbound ports must be named like `EntityService`, for example `PaymentService`.

##### application.port.out

Outbound ports are interfaces that define how business logic interacts with external systems, such as:
databases, kafka, third-party services. See the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for
naming conventions.

##### application.service

Services contain business logic that operates on domain objects.

Classes in this package provide implementations of the inbound service port interfaces defined in `application.port.in`
and must be named like `EntityServiceImpl`, for example `PaymentServiceImpl`.


---

### Adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Application` and `Domain` layers.

#### Package structure

```text
adapter/
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

##### adapter.out

Outbound adapters that implement interfaces defined in `application.port.out`. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for naming conventions.

##### adapter.in

Inbound adapters that invoke application logic and serve as entry points to the service.

Examples:

* REST API controllers
* Kafka consumers

HTTP error handling must be declared in an `GlobalExceptionHandler` class located in the `adapter.in.http` package.

See the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for controller/consumer naming conventions.

`adapter.in.http.idempotency` holds `HttpIdempotencyService`, which decides whether a mutating HTTP request should
proceed, replay a cached response, or be rejected, for `IdempotencyFilter`. It composes
`application.port.out.idempotency.HttpRequestStore` (a persistence port for idempotent_http_requests rows,
implemented by `adapter.out.idempotency`) but owns all of the actual decision logic itself - the store never
branches on what a conflict or a stale record means. `HttpIdempotencyService` lives here, not in `Application`,
because its whole purpose is specific to the HTTP idempotency-key pattern (an `Adapters`-owned concern), not to
orchestrating domain behavior.

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Database migrations
* Environment configuration
* Application configuration

This layer may depend only on the `Adapters` layer.

#### Package structure

Packages are grouped by technical responsibility or external technology integration.

```text
infrastructure/
└── config/
```