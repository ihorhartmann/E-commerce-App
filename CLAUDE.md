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

# Dependency versions

Every dependency added to any service (Gradle, Maven, Go modules, etc.) must pin an explicit version - never rely on
a transitive or BOM-managed version being resolved implicitly. `org.springframework.boot` is the sole exception: its
own artifacts (`spring-boot-starter-*`, the Gradle plugin, the Maven `spring-boot-dependencies` BOM import) are
versioned once, in a single place, and that one version governs all of them - so it still complies with this rule,
just centrally rather than per-dependency, instead of each `spring-boot-starter-*` needing its own repeated version
string.

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
  format `TechnologyPortName`, e.g. `HttpInventoryClient`, `PostgresOrderRepository`, `OutboxPaymentKafkaProducer`
  (technology = Outbox, for an adapter that writes to a transactional outbox table instead of publishing to Kafka
  directly). Exception: when the adapter's technology genuinely is Kafka itself (a direct client, not mediated
  through some other technology like an outbox), naming it `TechnologyPortName` would be redundant since the port
  name already states the technology (`EntityKafkaProducer`/`EntityKafkaConsumer`) - such an adapter just gets an
  `Impl` suffix instead, e.g. `EntityKafkaProducerImpl`. Alternatively, if the technology name itself already
  contains "Kafka" (e.g. `KafkaGo` for the kafka-go library), `TechnologyPortName` can be kept as-is by dropping the
  redundant `Kafka` from the port-name portion instead, e.g. `KafkaGoShipmentProducer` implementing
  `ShipmentKafkaProducer`.
* HTTP controllers must be named like `EntityController`, e.g. `OrderController`. If a controller doesn't map to a
  single domain entity (e.g. a third-party webhook handler, a health check), name it `PurposeController` instead,
  e.g. `StripeWebhookController`, `HealthController`.
* Kafka consumers must be named like `EntityKafkaConsumer`, e.g. `PaymentKafkaConsumer`.

# Start, shutdown

All services receive the environment variables required to connect to Kafka, database, etc. via env vars. If a variable
is missing, the service must not start and must log an error.

All services must have graceful shutdown, so that on receiving a termination signal the service can close all Kafka,
database connections and other resources if there are such.

# Kafka topics, retries and dead-lettering

* Every topic a service produces to or consumes from must be declared explicitly in that service's configuration, never
  left to broker auto-creation: Spring `NewTopic` beans in a `KafkaTopicConfig` (with `spring.kafka.admin.fail-fast`
  enabled) in Java/Kotlin services, and a startup step calling kafka-go's `Client.CreateTopics` (ignoring
  `TopicAlreadyExists`) in Go services. A topic shared by a producer and a consumer is declared by both, with identical
  settings. If the topics can't be declared because the broker is unreachable, the service must not start.
* Every consumed topic has its own dead-letter topic (DLT), named `<topic>.DLT` (the suffix is a constant next to the
  topic-name constants). The service that consumes the source topic declares its DLT, with the same partition count as
  the source topic and 30 days retention - longer than the source topic's, so a dead-lettered message can still be
  redriven well after the fact.
* A failing message is retried in place, blocking its partition, with exponential back-off: 5 attempts in total, waiting
  1s, 2s, 4s, 8s between them (15s), and then published to its DLT. All services use these same numbers.
* Failures are classified, and only transient ones are retried:
    * permanent failures - malformed payload, missing/invalid headers, a business rule rejecting the message, a
      referenced entity that doesn't exist, a request rejected outright by a third party - go straight to the DLT
      without any retry, since retrying can't change the outcome;
    * everything else (database errors, a third-party service being unavailable, ...) is transient and retried. A new
      kind of failure is transient unless it is explicitly listed as permanent: in Java/Kotlin, in the not-retryable
      list of the error handler config; in Go, in a check before the retry loop.
* A dead-lettered message keeps its original key, payload and business headers (`messageId`, `correlationId`,
  `messageType`), so redriving it reuses the same idempotency key. The failure itself is described by additional headers
  named as Spring's `DeadLetterPublishingRecoverer` names them, in every service, including the Go ones:
  `kafka_dlt-original-topic`, `kafka_dlt-original-partition`, `kafka_dlt-original-offset`,
  `kafka_dlt-original-consumer-group`, `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`.
* A message is never skipped, and its offset never committed, until it has either been handled or been published to
  the DLT. If publishing to the DLT itself fails, the consumer keeps retrying (blocking the partition) until it
  succeeds or the service shuts down. Because delivery is at-least-once (and, in the Spring services, the business
  handler even runs again on every such cycle), the handler must be idempotent. In Go services this has to be written
  explicitly: a handler error or a failed DLT publish must never fall through to the next message, and the DLT writer
  must wait for all in-sync replicas (kafka-go's default `RequireNone` acknowledges nothing, so a lost write would
  silently drop the message).
* Redriving from a DLT is always a manual, deliberate action, never automatic: republish to the original topic with the
  same key, payload and business headers (never a new `messageId`), without the failure headers, and only after the
  cause has been fixed.

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
* A DTO may contain factory logic for its creation (e.g. `OutboxMessage.createNew`/`createExisting`).
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
  * Exception: a literal that is genuinely interchangeable everywhere it appears in the file - the same figure is
    never itself the thing under test and carries no scenario-distinguishing meaning (e.g. a filler expiry offset
    passed only so a constructor's non-null future timestamp is valid) - may be hoisted into a shared file-level
    constant instead of being repeated or re-declared per method. This does not extend to identifying or
    scenario-distinguishing values such as ids, external ids, or names, even when the same literal happens to
    recur across several methods - those must stay local (a bare literal, or a per-method local variable per the
    base rule above), because tests often rely on such values being deliberately different between scenarios (e.g.
    two different ids proving an equals/inequality check), and a shared constant risks silently coupling
    otherwise-independent tests together.
* REST controller tests must assert on the response body's required fields (`jsonPath` in Java/Kotlin, decoded JSON
  in Go), not just the HTTP status code - a status-only assertion can't catch a wrong/missing field or a malformed
  body. Exception: responses with no body by design (e.g. `204 No Content`, or a `2xx` endpoint that intentionally
  returns no payload) have nothing to assert on and are exempt.
* When a test names its actual/expected comparison variables explicitly, use `got`/`want` in Go and `actual`/`expected`
  in Java/Kotlin - never mix the two languages' vocabularies. Within one scope (a test method, or one Go `t.Run`
  block), if there's only a single such comparison, the bare names are enough. If more than one comparison happens in
  the same scope and at least one side needs a qualifier to stay unambiguous (e.g. `wantOrder`, `expectedOrderId`),
  the other side of that same pair must be qualified too (e.g. `gotOrder`/`wantOrder`, `actualOrderId`/`expectedOrderId`)
  - never leave one side of a pair qualified while the other stays bare. This also applies when the "want"/"expected"
  side is an already-existing variable rather than a freshly declared one (e.g. reusing a `correlationID` that was
  passed into the call under test as the expected value) - the generic `got`/`actual` side must still be qualified to
  match it (e.g. `gotCorrelationID`/`correlationID`, not a bare `got`/`correlationID`), since a bare `got` shadowed
  across several such comparisons in the same scope is exactly as ambiguous as a bare `got` next to a qualified `want`.
  The only exemption is a handful of universal, non-negotiable Go idioms that are never renamed regardless of context -
  chiefly `err` (never `gotErr`) and `ok` (never `gotOk`) - because Go convention already treats those names as fixed
  regardless of what they're being compared against. Having an otherwise-descriptive name (e.g. a multi-return value
  like `messageType` from `messageType, messageID, correlationID, err := parseHeaders(headers)`) is NOT by itself a
  reason to skip qualification: if it's compared against a qualified `want*`/`expected*` counterpart, it must become
  `gotMessageType` just the same - the pairing rule applies to anything actually playing the got/actual role in a
  comparison, not just to bare `got`/`actual`.
