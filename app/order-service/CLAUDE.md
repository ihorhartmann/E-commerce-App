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
│   └── exception/
└── value/
    └── exception/
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

Only the base of every exception this service raises on purpose - `OrderServiceException`, its kinds and `ErrorCode`;
see [Error handling](#error-handling). The exceptions the domain objects themselves raise live next to them, with
their codes: `entities/model/exception` (`OrderErrorCode`, e.g. `OrderNotCancellableException`) and
`entities/value/exception` (`ValueErrorCode`, e.g. `InvalidMoneyAmountException`).

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
│   │       ├── dto/
│   │       └── exception/
│   └── out/
│       ├── internal/
│       │   └── dto/
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
│   │   ├── dto/
│   │   ├── exception/
│   │   ├── idempotency/
│   │   └── security/
│   ├── kafka/
│   │   ├── dto/
│   │   └── exception/
│   └── scheduled/
└── out/
    ├── http/
    │   ├── common/
    │   │   └── dto/
    │   ├── inventory/
    │   │   ├── dto/
    │   │   └── exception/
    │   └── payment/
    │       ├── dto/
    │       └── exception/
    ├── kafka/
    │   └── dto/
    └── repository/
        ├── entity/
        └── exception/
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

`interfaceadapter.in.scheduled` holds periodic sweeps (e.g. `HttpRequestSweep`, `ProcessedKafkaMessageSweep`) that
run on a timer. A scheduled job is a self-triggered entry point - it decides on its own schedule to invoke a
business port, the same role a Kafka consumer or REST controller plays for its own trigger - so it belongs in
`Interface adapters`, not `Frameworks`, even though nothing here is framework-specific glue.

##### interfaceadapter/out

Outbound adapters that implement interfaces defined in `usecase/port/out`. See the
[root CLAUDE.md](../../CLAUDE.md#ports-and-adapters-naming) for naming conventions.

`interfaceadapter/out/http` has a package per sibling service - `inventory`, `payment` - each with its clients and
its own `dto` and `exception` (with its `InventoryErrorCode`/`PaymentErrorCode`), plus `common` for what the clients
share: the retry and circuit-breaker config, the logging interceptor and `dto.ProblemResponse`.

---

### Frameworks

Contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Kafka consumer/producer/error-handler configuration

This layer may depend only on the `Interface adapters` layer. In rare cases, configuration that wires beans
together may also reference the `Use cases` layer directly - e.g. a `@Bean` method that must return an outbound
port interface (per the Dependency Inversion rule in the root CLAUDE.md), or a shared constant such as a Kafka topic
name in `usecase/port/common`. It must never contain or invoke business logic from it.

These implementations do not contain business-specific logic and are therefore implemented at the framework level.

#### Package structure

```text
frameworks/
├── config/
├── kafka/
└── security/
```

`frameworks/config` holds only `@Configuration` classes. The framework glue they wire in lives next to them, in a
package per framework: `frameworks/kafka` (e.g. `RetryableDeadLetterPublishingRecoverer`), `frameworks/security`
(e.g. `RolesClaimGrantedAuthoritiesConverter`). A class that turns a framework event into one of this service's own
types is an interface adapter, not glue, even if a framework calls it: Spring Security's entry point and
access-denied handler, which turn a rejected request into this service's exceptions, live in
`interfaceadapter.in.http.security`.

## Error handling

Every exception this service raises on purpose extends `entities.exception.OrderServiceException` through one of its
kinds - `InvalidException`, `NotFoundException`, `ConflictException`, `TransientException`, `UnavailableException` -
or directly, for an internal failure. A concrete exception passes its code, a constant internal message and its data
as attributes right in its class header:

```kotlin
class OrderNotFoundException(val orderId: Long) :
    NotFoundException(RepositoryErrorCode.ORDER_NOT_FOUND, "Order not found", mapOf("orderId" to orderId))
```

* The code (`ORDER.ORDER_NOT_FOUND` - `ErrorCode` adds the service prefix) and its title are public and the same for
  every occurrence; each layer declares its codes as an enum implementing `ErrorCode`, next to the exceptions that use
  them (`OrderErrorCode`, `RepositoryErrorCode`, `InventoryErrorCode`, ...). Keep codes specific (`ORDER_NOT_FOUND`,
  not `NOT_FOUND`). The kind alone decides the HTTP status, whether a Kafka message is retried or dead-lettered at
  once (`permanent`: invalid, not found, conflict), and whether `IdempotencyFilter` caches the response or releases the
  key.
* The internal message and the attributes never leave the service; keep the internal message constant and put every
  value in the attributes. The external message is the only per-occurrence text a caller sees.
* An exception gets its code where it first enters this service's code: a domain rule, `PostgresOrderRepository`
  turning a JPA exception into `DatabaseFailureException`, `HttpInventoryClient` turning an error response into
  `InsufficientStockException`. An exception that already is one of ours is rethrown as it is; wrapping it as the cause
  of another one replaces its code - do that only on purpose (e.g. `InconsistentOrderException`).
* An exception lives where the root CLAUDE.md puts exceptions: next to a port only if the port's callers need it
  (`OrderNotFoundException`, `ConcurrentUpdateException`, `InvalidPageException`), otherwise next to the adapter that
  throws it (`interfaceadapter.out.http.inventory.exception`, `interfaceadapter.out.repository.exception`).
* The domain has no `require`/`check`: every rule it enforces throws an exception of ours, and which one depends on
  who can break the rule, not on the rule:
    * a request or a message - an `InvalidException`/`ConflictException` (e.g. `InvalidAddressException`);
    * stored data - `InconsistentOrderException` (internal), thrown by `createExisting` and by `fromOrderEntity` for
      a value the stored data fails to build;
    * only this service's own code - a bug - `OrderInvariantViolatedException` (internal), e.g. `createNew` without
      items.

  An entity's `createNew` and `createExisting` check the same rules (a shared `findBrokenRule`), each reporting a broken
  one its own way. The two internal codes are kept apart, so a log tells whether to look at the data or at the code.
* Log an exception once, where it is handled - `GlobalExceptionHandler`, `OrderKafkaConsumer` - with every attribute
  of `attributesOf(exception)` as a key-value pair of its own.

HTTP error responses are RFC 9457 problem details (`type` `urn:ecommerce:error:<code>`, `title`, `status`, `detail` =
the external message, `instance`, and the members `code` and `retryable`), written only by `GlobalExceptionHandler`.
Spring MVC calls it for the exceptions of the controllers; `IdempotencyFilter` and Spring Security's
`ProblemAuthenticationEntryPoint`/`ProblemAccessDeniedHandler` hand theirs to it through the `handlerExceptionResolver`
bean. A status that differs from the kind's default (401, 403, 409 for a request in progress, 422) is listed in its
`statusOf`. A transient or unavailable failure also gets `Retry-After`.

Kafka: `OrderKafkaConsumer` rethrows every failure as it is, and `KafkaErrorHandlerConfig` gives a permanent one
(`isPermanentFailure`) no retries, so it is dead-lettered at once; any other failure is retried.
