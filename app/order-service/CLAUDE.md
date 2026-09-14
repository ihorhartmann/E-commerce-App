# Order Service Architecture

**Architecture:** Clean

**Language:** Kotlin

**Technologies:** SpringBoot, Hibernate

## Layers and Packages

### Entities

Contains domain objects that represent business concepts and rules.

This layer must not depend on any other layer.

#### Package structure

```text
entities/
├── exception/
├── model/
└── value/
```

##### entities/model

Domain models with their own identity.

Examples:

* `Order`
* `OrderItem`

##### entities/value

Value objects used by domain models.

Value objects do not have their own identity.

Examples:

* `Money`
* `Address`

##### entities/exception

Exceptions raised by domain objects themselves (as opposed to exceptions raised by a specific use case or adapter), for
example `DomainException`.

---

### Use cases

Contains application business logic and ports that define how business logic interacts with external systems.

This layer depends only on the `Entities` layer.

#### Package structure

```text
usecase/
├── port/
│   ├── common/
│   │   └── kafka/
│   ├── in/
│   │   ├── saga/
│   │   │   └── event/
│   │   └── usecase/
│   │       └── dto/
│   └── out/
│       ├── internal/
│       │   ├── dto/
│       │   └── exception/
│       ├── kafka/
│       │   └── dto/
│       └── repository/
│           └── exception/
├── saga/
└── ...
```

`usecase/port/out/internal` holds outbound ports for synchronous calls to this system's own sibling services
(Inventory, Payment) - named `internal`, not `http`, because the transport (HTTP today, potentially gRPC later) is
an adapter-level detail the port itself stays agnostic to. Ports for third-party/vendor integrations that need
translation between our domain and a foreign API belong in their own package instead (e.g. `usecase/port/out/gateway`),
not here.

##### usecase/port/in/usecase

Inbound ports are interfaces that define how business logic is invoked by the `Interface adapters` layer.

Each interface exposes a single, specific application scenario as an `execute` method, and must be named
`ActionEntityUseCase`, for example `PlaceOrderUseCase` or `GetOrderUseCase`.

##### usecase/port/in/saga

Inbound saga ports, named `SagaNameSaga`, for example `FulfillOrderSaga`. `usecase/port/in/saga/event` contains the
event types a saga reacts to or emits.

##### usecase/port/out

Outbound ports are interfaces that define how business logic interacts with external systems, such as:
databases, kafka, third-party services. See the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for
naming conventions.

##### usecase/port/common

Classes shared between an `Interface adapters/out` adapter and an `Interface adapters/in` adapter (e.g. Kafka topic
names, header names, event-type names) must not live inside either adapter's own package, since `in` and
`out` adapters are siblings and neither may depend on the other. Put them in a dedicated package under
`usecase/port/common` instead (e.g. `usecase/port/common/kafka`), since both are already allowed to depend on
`Use cases`.

##### usecase/saga

Contains saga orchestration logic for long-running business processes that span multiple services.

Classes in this package provide implementations of the inbound saga port interfaces defined in
`usecase/port/in/saga`.

Sagas coordinate multiple application steps by:

* Executing a sequence of operations across services
* Tracking the current saga state
* Handling successful completion
* Triggering compensating actions when a step fails

##### Use case implementations

Classes implementing the inbound use case ports live directly in the `usecase` package, named `ActionEntityUseCaseImpl`,
for example `PlaceOrderUseCaseImpl` or `GetOrderUseCaseImpl`.

---

### Interface adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Use cases` and `Entities` layers.

#### Package structure

```text
interfaceadapter/
├── in/
│   ├── http/
│   │   └── dto/
│   └── kafka/
│       └── dto/
└── out/
    ├── http/
    │   └── dto/
    ├── kafka/
    │   └── dto/
    └── repository/
```

##### interfaceadapter/in

Inbound adapters that invoke application logic and serve as entry points to the service.

Examples:

* REST API controllers
* Kafka consumers

HTTP error handling must be declared in a `GlobalExceptionHandler` class located in the `interfaceadapter.in.http`
package.

See the [root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for controller/consumer naming conventions.

`interfaceadapter.in.http.idempotency` holds `HttpIdempotencyService`, which decides whether a mutating HTTP request
should proceed, replay a cached response, or be rejected, for `IdempotencyFilter`. It composes
`usecase.port.out.idempotency.HttpRequestStore` (a persistence port for idempotent_http_requests rows, implemented
by `interfaceadapter.out.idempotency`) but owns all of the actual decision logic itself - the store never branches
on what a conflict or a stale record means. `HttpIdempotencyService` lives here, not in `Use cases`, because its
whole purpose is specific to the HTTP idempotency-key pattern (an `Interface adapters`-owned concern), not to
orchestrating domain behavior.

##### interfaceadapter/out

Outbound adapters that implement interfaces defined in `usecase/port/out`. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for naming conventions.

---

### Frameworks

Contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Kafka consumer/producer/error-handler configuration

This layer may depend only on the `Interface adapters` layer.

These implementations do not contain business-specific logic and are therefore implemented at the framework level.

#### Package structure

```text
frameworks/
└── config/
```
