# Overall Architecture

The system consists of four services:

* Inventory
* Order
* Payment
* Shipping

Services communicate with each other through Kafka and HTTP and expose REST APIs for frontend communication.

All services are located in the `app` directory.

The **Order Service** acts as the orchestrator for the other services.

## Services

1. **Inventory**

* Architecture: Onion
* Language: Go
* Technologies: GORM, Fiber, kafka-go

2. **Order**

* Architecture: Clean
* Language: Kotlin
* Technologies: Spring Boot, Hibernate

3. **Payment**

* Architecture: Hexagonal
* Language: Java
* Technologies: Spring Boot, jOOQ

4. **Shipping**

* Architecture: Hexagonal
* Language: Go
* Technologies: GORM, Gin, kafka-go

# Code principles

The Domain/Entity layers for each service must follow the Information Expert principle. A class/struct in the domain
must know everything relevant to it - no more, no less.

Constructors and factory methods must depend on interfaces (ports), not concrete implementations, and must return
interfaces rather than concrete implementations, wherever an interface for that dependency/return type exists or
reasonably should exist (Dependency Inversion). This applies across all layers in every service - e.g. a use
case/service must be wired against a repository/client/producer interface (not the concrete adapter struct/class that
implements it), and a constructor function/factory (e.g. Go's `NewXxx`) must return the interface type, not the concrete
struct pointer. It does not apply to dependencies or return values that are inherently concrete and have no meaningful
interface (e.g. value objects, DTOs, framework types like `KafkaTemplate`).

# Go-specific conventions

* Use lowerCamelCase identifiers by default.
* Use UpperCamelCase only for exported types, functions, methods, constants, and variables.
* If a struct intentionally implements an interface, the interface must be explicitly embedded in the struct.
* HTTP status codes must always be taken from the `net/http` package.
* Acronyms/initialisms (e.g. `DTO`, `ID`, `URL`, `HTTP`) must stay fully uppercase in identifiers (standard Go style),
  e.g. `MoneyDTO`, not `MoneyDto`.

# Ports and adapters naming

* Outbound ports (interfaces that define how business logic interacts with external systems - databases, Kafka,
  third-party services) are grouped by transport or technology type and must be named like `EntityRepository`,
  `EntityKafkaProducer`, etc.
* Outbound adapter implementations (the concrete classes/structs that implement those ports) must be named in the
  format `TechnologyPortName`, e.g. `HttpInventoryClient`, `PostgresOrderRepository`. Exception: Kafka producer/consumer
  adapters just get an `Impl` suffix instead (e.g. `PaymentKafkaProducerImpl`), since the port name already states the
  technology (`EntityKafkaProducer`/`EntityKafkaConsumer`), making a `Kafka`-prefixed adapter name redundant.
  Alternatively, if the technology name itself already contains "Kafka" (e.g. `KafkaGo` for the kafka-go library),
  `TechnologyPortName` can be kept as-is by dropping the redundant `Kafka` from the port-name portion instead, e.g.
  `KafkaGoShipmentProducer` implementing `ShipmentKafkaProducer`.
* HTTP controllers must be named like `EntityController`, e.g. `OrderController`. If a controller doesn't map to a
  single domain entity (e.g. a third-party webhook handler, a health check), name it `PurposeController` instead,
  e.g. `StripeWebhookController`, `HealthController`.
* Kafka consumers must be named like `EntityKafkaConsumer`, e.g. `PaymentKafkaConsumer`.

# Start, shutdown

All services receive the environment variables required to connect to Kafka, database, etc. via env vars. If a variable
is missing, the service must not start and must log an error.

All services must have graceful shutdown, so that on receiving a termination signal the service can close all Kafka,
database connections and other resources if there are such.

# Exceptions, errors, DTOs, and Repository Entities

* In Java/Kotlin services, exceptions must live in a separate `exception` package, located next to the file (s) that use
  them.
* In Go services, errors must live in an `errors.go` file, located next to the file (s) that use them. If several
  distinct entities/concerns share a package, split their errors into multiple logically-grouped files instead of one
  catch-all `errors.go`, e.g. `product_errors.go`, `reservation_errors.go`, plus a `common_errors.go` for errors
  shared across them.
* DTOs (Java/Kotlin/Go) must live in a separate `dto` package, located next to the file (s) that use them.
* Repository entities (the DB-row-shaped struct/class used by a repository adapter, distinct from the domain model) must
  live in a separate `entity` package, next to the repository that uses them. Entity class/struct names must match the
  name of the database table they represent (e.g. table `payments` -> `Payment`).
* Exceptions, errors, and DTOs must be declared at the outermost architectural layer possible:
    * if a DTO/exception is needed in the signature of an inner layer's port (interface) - declare it next to that port;
    * if it's only needed by a specific implementation of that port in an outer layer (e.g. an adapter) - declare it
      next to that adapter, not next to the port.
* Prefer outbound ports with primitive/domain-typed parameters (e.g.
  `fun publishCreateShipment(correlationId: String, orderId: Long, address: Address)`)
  over a port method that takes a shared envelope/message type, so the payload shape stays entirely inside the adapter
  implementation that needs it, instead of leaking wire-format/serialization details into the application/domain layer
  calling the port.
* Mappers (functions/methods) must be named `fromDtoName`/`toDtoName`, e.g. `fromStripeStatus`, `toPaymentResponse`
  (the same convention applies to repository entity mappers, e.g. `fromPaymentEntity`, `toPaymentEntity`), and by
  default declared next to the DTO/entity they build (in the same file), rather than gathered into a dedicated
  `mapper` class/package:
    * In Go and Kotlin services: a plain top-level/package-level function without a receiver.
    * In Java services (which has no top-level functions): a static method declared on the DTO/entity itself, for
      both directions, so DTO instances stay pure data with zero attached instance behaviour - e.g.
      `PaymentResponse.from(payment)` for the `to<Dto>` direction, and `SubmitPaymentRequest.toMoney(request)`
      (taking the DTO as an explicit parameter, not `request.toMoney()`) for the `from<Dto>` direction. Never place
      either on the domain class, since that would make the domain layer depend on DTOs.
* Trivial DTOs built by direct 1:1 field copying (no nested DTOs, no list/collection transformation, no type
  conversion or validation) do not need a mapping function at all - construct them directly at the call site, e.g.
  `CancelReservationCommand(orderId)`. Add a `from`/`to` function only when skipping it would leave visible mapping
  noise in the calling code: the DTO is composed of other DTOs, the mapping involves a list/collection
  transformation, or there is real conversion/validation logic (e.g. parsing a string into an enum).
* DTOs for HTTP controllers must be named `EndpointNameRequest`/`EndpointNameResponse`, e.g. `PlaceOrderRequest`,
  `PlaceOrderResponse`.
* DTOs for Kafka must be named `MessageNameMessageType`, e.g. `CreateShipmentCommand`,
  `PaymentConfirmedEvent`. Exception: if several message types carry an identical payload shape (e.g. a family of
  status-changed events that all just carry an id), a single shared DTO may be reused across them instead of
  duplicating structurally-identical structs/classes per message type.
* If a DTO is used for both the request and the response of the same resource, name it
  `NameDto` (e.g. `ProductDto`) instead of separate `Request`/`Response` names.
* In Java/Kotlin services, acronyms/initialisms in identifiers are capitalized only on the first letter (standard
  Java/Kotlin style), e.g. `MoneyDto`, not `MoneyDTO` - the opposite of the Go convention above.
* If a DTO is composed of other DTOs, building those nested DTOs must be delegated to the mapper function/method
  responsible for mapping that specific nested DTO, rather than built inline by the outer mapper - each mapper stays the
  Information Expert for its own DTO.
* When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused
  mapping methods.

# Tests

* In Java/Kotlin services (JUnit5), every test class must be explicitly annotated
  `@TestInstance(TestInstance.Lifecycle.PER_METHOD)`, even though it is JUnit5's default. Test isolation (mocks,
  captured state) typically relies on a fresh instance being created per test method; leaving the lifecycle implicit
  means a later change to `PER_CLASS` (e.g. to share expensive `@BeforeAll` setup) can silently break that isolation.
  Making the choice explicit on every class prevents that.
* Application-layer and domain-layer unit test names must follow a `MethodName` + separator + `TestDescription`
  pattern: the exact name of the production method under test, then a description of the scenario. The separator
  depends on what the language's test-name syntax allows:
    * In Kotlin, backtick-quoted test names allow most characters but not `:` (illegal in a JVM method name), so use
      a literal ` - ` between the method name and a plain-language description, e.g.
      `` `execute - places the order, reserves stock, and submits payment` ``.
    * In Java, method names can't contain spaces or `:`, so use an underscore followed by a camelCase description,
      e.g. `submitPayment_createsAPendingPaymentFromTheGatewayResult`.
    * In Go, the existing `TestType_MethodName` top-level function combined with `t.Run("test description")`
      subtests already satisfies this pattern (the subtest path reads as `TestType_MethodName/test_description`) -
      no different convention is needed there.
    * A Java record's compact constructor or a Kotlin class' primary constructor/`init` block has no method name of
      its own to reuse, so use the literal word `constructor` as the `MethodName` part, e.g.
      `constructor_rejectsANegativeAmount` (Java), `` `constructor - rejects a negative amount` `` (Kotlin). Go
      constructors are already named functions (`NewProduct`, `NewShipment`), so this placeholder isn't needed
      there.
* Application-layer and domain-layer tests must cover every distinct branch a method defines: each happy-path
  outcome (e.g. an `if`/`when` picking a different action per state or flag - not just one representative "sunny
  day" case) and each catch/error-handling branch with its own logic (a Java/Kotlin `catch`, a Go `if err != nil`,
  doing a state transition, a compensating action, a rethrow with a side effect, etc). Skip calls whose error isn't
  caught - it just propagates by default, which is language behavior, not application logic, and no test can
  protect it from regressing. This includes factory methods (e.g. a domain model's `create`/`NewXxx`): test them by
  their own name like any other method, separately from `constructor` if they delegate to it.
* Tests must verify the number of times each mock/collaborator method is called (`Mockito.verify(mock, times(n))`
  or `verifyNoInteractions`/`verifyNoMoreInteractions` in Java, `verify(exactly = n)` in Kotlin/MockK,
  `.Times(n)` in Go/gomock), not just assert on the method's return value. This is what actually distinguishes
  one branch from another (e.g. two happy-path branches that return the same shape of result but publish a
  different number of Kafka messages, or none at all, are indistinguishable by a return-value assertion alone).
* Application-layer unit tests must not re-verify domain business-rule validation (e.g. a domain method rejecting
  an invalid state or amount). That validation is owned and already covered by the
  domain layer's own unit tests; duplicating it at the application layer tests the domain, not the use case.
* Within a single test method (a Go `t.Run` closure counts as one), any literal value used 2 or more times must be
  extracted into a local variable declared at the top of that method/closure and reused at every occurrence,
  instead of repeating the literal - e.g. an id passed into a constructor call and then re-checked in an assertion,
  or a correlation id passed into the call under test and then repeated in a `verify`/`EXPECT` call. Scope the
  variable to that one method only; do not hoist it to a shared file-level constant, and do not extract a value
  that only appears once.
