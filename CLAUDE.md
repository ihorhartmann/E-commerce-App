# Overall Architecture

The system consists of four business services:

* Inventory
* Order
* Payment
* Shipping

plus the **Gateway**, the single entry point for clients, **Keycloak** as the identity provider, and the **Web UI**,
a browser client of the Gateway.

Services communicate with each other through Kafka and HTTP and expose REST APIs for frontend communication.

All services are located in the `app` directory; the Keycloak realm (roles, clients, test users) is
`docker/keycloak/realm-ecommerce.json`.

The **Order Service** acts as the orchestrator for the other services.

## Authentication and authorization

* Keycloak issues the access tokens (JWT); realm roles (`CLIENT`, `COURIER`, `ADMIN`, plus service roles such as
  `INVENTORY_RESERVE`) are in the claim named by `OIDC_ROLES_CLAIM`, the user id is `sub` (a UUID).
* The Gateway logs browser users in (Authorization Code + PKCE, tokens kept in the server-side session) and relays
  their token; Bearer clients (Postman, e2e tests) send their own. It only checks that a request is authenticated.
* The Web UI's nginx forwards the Gateway's paths (`/api`, `/oauth2`, `/login`, `/logout`), so the browser sees one
  origin and the session cookie works without CORS. The Gateway knows nothing about the UI: it only builds its
  redirect URIs from the `X-Forwarded-*` headers nginx sets.
* Every service is an OAuth2 resource server: it validates the token itself (JWKS, issuer), checks the caller's role
  per endpoint (anything not listed is denied), and its use cases check resource ownership (another customer's
  resource is reported as not found).
* Service-to-service HTTP calls carry the calling service's own token (OAuth2 client credentials), never a user's.
  Kafka messages carry no token: consumers trust the ids in messages from the internal services.

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

5. **Gateway**

* Architecture: none (no domain - routing and security configuration only)
* Language: Kotlin
* Technologies: Spring Cloud Gateway (WebFlux), Spring Security OAuth2 Client + Resource Server

6. **Web UI**

* Architecture: MVVM (screen -> ViewModel -> API client), packages by feature - see `app/web-ui/CLAUDE.md`
* Language: Kotlin/JS
* Technologies: Compose HTML, Ktor Client, Stripe.js; served by nginx

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

Declare a local variable where it is first needed, not up front at the top of the function: the reader meets it next
to the code that uses it, and its scope stays as narrow as the code allows. This applies to tests too, including the
literals extracted into local variables under the test rules below.

# Domain entities and repositories

* Entities (domain objects with an identity) are mutable: a transition changes the entity in place and returns
  nothing, or only what it creates or reports (e.g. a new child object, or a boolean saying whether anything
  changed) - never a modified copy of itself. Every transition checks everything before changing any field, so a
  rejected transition leaves the entity exactly as it was. Value objects stay immutable.
* Every entity stored through its own repository has a `version` column for optimistic locking, and every
  update/delete of it applies only `WHERE id = ? AND version = ?` (with JPA, `@Version`). When nothing matches,
  the repository tells a missing entity (not-found error) from a concurrently changed one
  (`ConcurrentUpdateException`/`ErrConcurrentUpdate`), which is transient: retried by Kafka consumers and mapped
  to `503` over HTTP. A child entity saved only as part of its aggregate (e.g. `OrderItem`) is covered by the
  aggregate root's version.
* Repository `create`/`update` methods return the entity as the database holds it after the write, read back in
  the same statement (`RETURNING` in jOOQ and GORM; with JPA, what `save` returns) - never re-built from the
  argument, and never through an extra `SELECT`. An optimistic-locking version is incremented only in the SQL
  (`version = version + 1`), never in application code.
* After `create`/`update`, the object passed in is spent: continue with the returned one, or with none.
    * Pass a new entity to `create` inline (`repository.create(Payment.createNew(orderId, amount))`), so the
      unsaved draft never gets a name. Where that isn't possible, reassign the same variable
      (`shipment, err = repository.Create(ctx, shipment)`); where even that isn't possible (a Kotlin parameter,
      which is always `val`), shadow the parameter with a local `val` of the same name
      (`val order = orderRepository.save(order)`), so the draft is no longer reachable below that line.
    * If the result of `update` is needed, reassign the same variable or return it directly
      (`return repository.Update(ctx, product)`); otherwise ignore it (`_` in Go). Never keep it in a second
      variable next to the original. Inside a Java lambda (e.g. a `TransactionTemplate` callback), which can't
      reassign a captured variable, make the write the last statement and return its result.
    * Variables are named after the entity, not its state: `payment`, not `initiatedPayment`/`pendingPayment`.
* Never save an object whose version is stale: the one passed to an earlier save (continue with the one it
  returned instead), or one loaded before a step in which others could have changed it - another transaction, an
  external call - which is reloaded instead. The optimistic version check rejects such a save.

# Dependency versions

Every dependency added to any service (Gradle, Maven, Go modules, etc.) must pin an explicit version - never rely on
a transitive or BOM-managed version being resolved implicitly. `org.springframework.boot` is the sole exception: its
own artifacts (`spring-boot-starter-*`, the Gradle plugin, the Maven `spring-boot-dependencies` BOM import) are
versioned once, in a single place, and that one version governs all of them - so it still complies with this rule,
just centrally rather than per-dependency, instead of each `spring-boot-starter-*` needing its own repeated version
string. The same exception covers the libraries whose versions that Spring Boot BOM manages and that a Spring Boot
starter already brings in - Micrometer (`io.micrometer:*`) and OpenTelemetry (`io.opentelemetry:*`): a service that
uses them directly declares them without a version, so they always stay in step with the one Spring Boot version
instead of silently overriding it after a Spring Boot upgrade.

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

# Logging

* Every service logs to stdout, one JSON object per line, with the same field names: `@timestamp`, `level`,
  `message`, `traceId`, `spanId`, `correlationId` (the saga's, see below), and - while a Kafka message is handled -
  `messageType`, `messageId`. Spring services get it from `logging.structured.format.console: logstash`, Go services
  from `telemetry.NewLogHandler`. Alloy reads the lines from Docker and pushes them to Loki, labelled with the compose
  service, as `service_name` (OpenTelemetry's name, which Grafana's Logs Drilldown groups by), and the level only;
  Loki adds `detected_level` (structured metadata) for the Drilldown's level filter.
* Every line of a saga's work carries its `correlationId`, so one query finds all of them across the services:
    * work that came with one keeps it - a Kafka message in its header, a call from another of our services in the
      `X-Correlation-Id` HTTP header (the gateway drops it from a client's request) - and passes it on: in the headers
      of the messages it writes to the outbox, and in `X-Correlation-Id` of its own calls to our services;
    * work that starts a chain - a client's request, a Stripe webhook, a courier's request, a scheduler - derives it
      from what it handles, as soon as it knows it: `CorrelationTagger.tag` in Spring (the span and the MDC),
      `logging.ResolveCorrelationID` in Go (a request's `ctx`);
    * whoever starts the work removes it from the MDC once the work is done (the request filter, the Kafka consumer);
      a loop over many sagas in one unit of work (a sweep) logs it per line as a key-value instead.
* An outbox relay logs each message it sends as DEBUG `sending kafka message` (`topic`, `messageType`, `messageId`,
  `correlationId`), in the trace the message was written in.
* The trace and the Kafka message's ids come from the context, not from each log call: in Spring from the MDC
  (Micrometer Tracing's, and the one the Kafka consumer fills), in Go from the `ctx` - so log with slog's `*Context`
  functions (`slog.InfoContext(ctx, ...)`) wherever a request's or a message's `ctx` is at hand, and give every line of
  a unit of work a value with `logging.WithAttrs`. Don't pass those fields to a single log call again: Spring Boot's
  structured logging drops a line that has a key twice - in the MDC and as a key-value, or as two key-values - so a
  key-value whose key may already be there (an exception's attributes) is left out, as `failureKeyValues` does.
* A log message is a constant text, the same for the same event in every service (`received http request`,
  `successfully processed kafka message`, `kafka message dead-lettered`, ...); every value goes in as a key-value of
  its own - `LOG.atInfo().setMessage(...).addKeyValue(...)` in Spring, slog attrs in Go - never into the message, so
  Loki can filter on it and count the event.
* Levels: ERROR - a failure that needs attention (a 5xx, a dead-lettered message); WARN - something unusual but
  handled (a rejected request or message, a retry); INFO - one or two lines per unit of work in normal operation
  (a request or message handled, a refund created); DEBUG - the detail for an investigation (`received ...`,
  `sending ...`). A Kafka message that fails is logged the same way in every service: each failed attempt as a WARN -
  `kafka message rejected` for a permanent failure, `kafka message handling failed` for a transient one (retried) -
  and, once it gives up, a single ERROR `kafka message dead-lettered`; one ERROR is one message to redrive. Every
  service logs INFO and up by default; `LOG_LEVEL` in `.env` sets it for all of them (in
  docker-compose it becomes `LOG_LEVEL` for the Go services and `LOGGING_LEVEL_COM_HARTMANN` for the Spring ones).

# Metrics and health

* Every service pushes its metrics over OTLP to Alloy, which forwards them to Prometheus, every 15s, with
  `service.name` and a `service.instance.id` per replica (the host name). Durations are in seconds, with the same
  explicit buckets everywhere (`0.005 ... 10`s), so the percentiles of every service compare.
* A metric that several services record has the same name and attributes in every one of them:
  `http.server.request.duration` (`http.request.method`, `http.route`, `http.response.status_code`),
  `ecommerce.kafka.message.duration` (`topic`, `message.type`, `outcome` = `success`/`failed`/`rejected`, one per attempt),
  `ecommerce.kafka.message.dead_lettered`, `ecommerce.errors` (`error.code`, `transport`), and the outbox relay's
  `ecommerce.outbox.messages.published`/`ecommerce.outbox.relay.failures`. Attributes are low-cardinality only: a route
  pattern, never a path; a code, never an id.
* Custom meters are recorded globally - Micrometer's `Metrics.globalRegistry` in Spring, the global `otel.Meter` in Go -
  not injected, so code without a registry (a unit test) records nothing.
* What describes the system rather than a replica - the outbox backlog, orders by status, stuck orders, saga durations,
  payments and shipments by status - is read from the database by Alloy's postgres exporter
  (`docker/alloy/postgres-queries.yaml`), never kept as a gauge in each replica. Consumer lag comes from Alloy's Kafka
  exporter.
* A metric counts, it never lists: which orders are stuck is no label of a series, but a table Grafana reads straight
  from Postgres, as `grafana_reader` (read-only, `docker/postgres/init-grafana-reader.sh`).
* Alerts are Prometheus rules (`docker/prometheus/rules`), on symptoms only, each with a `severity` (`critical` - act
  now, `warning` - look at it today), a `service`, and `summary`/`description`/`dashboard` annotations; Alertmanager
  (`docker/alertmanager`) mails them, one template per severity, and a critical alert mutes the warnings of its
  service. A new alert goes there - not into Grafana - with a unit test in `docker/prometheus/tests/alerts_test.yaml`
  (`promtool test rules`: when it fires, its labels and its text); `entrypoint.sh check` checks Alertmanager's
  configuration and routing offline. CI runs both (`unit-alerting`).
* Every service answers `/health/liveness` (the process is up - no dependency checked) and `/health/readiness` (and
  `/health`): the startup has finished, the shutdown hasn't begun, and the database answers - Kafka and the other
  services are no part of it. Spring gets them from Actuator (`probes.enabled`, readiness group `readinessState,db`), Go
  from its `HealthController`, set ready at the end of `main.go`'s startup and not ready as the first step of the
  shutdown. Health requests need no token and are neither traced, nor logged, nor counted. docker-compose waits for
  readiness (`service_healthy`).

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
* The consumer checks the business headers itself (`messageType`, `messageId`, `correlationId`) - in Spring they are
  `@Header(required = false)` parameters, not ones Spring's binding rejects - so a message missing one fails with our
  `INVALID_MESSAGE_HEADERS` code like any other invalid message, instead of a framework exception that bypasses the
  consumer. The Kafka error codes are the same in every service (`INVALID_MESSAGE_HEADERS`, `MALFORMED_MESSAGE`).
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
* What still waits in the DLTs is the lag of the `dlt-redrive` consumer group, which nothing consumes with: its offset
  in each DLT is a bookmark - everything before it has been redriven or discarded. `kafka-init`
  (`docker/kafka/init-dlt-redrive.sh`) sets it at the start of every DLT that has none yet and never moves an existing
  one; the Grafana dashboards show the lag. After redriving or discarding a message, move the bookmark past it:
  `kafka-consumer-groups.sh --group dlt-redrive --topic <dlt>:<partition> --reset-offsets --to-offset <its offset + 1>
  --execute`. Kafka keeps the bookmarks 30 days (`KAFKA_OFFSETS_RETENTION_MINUTES`), as long as the DLT messages.

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
  extracted into a local variable declared where the value is first needed in that method/closure and reused at
  every occurrence,
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
