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

# Ports and adapters naming

* Outbound ports (interfaces that define how business logic interacts with external systems - databases, Kafka,
  third-party services) are grouped by transport or technology type and must be named like `EntityRepository`,
  `EntityKafkaProducer`, etc.
* Outbound adapter implementations (the concrete classes/structs that implement those ports) must be named in the
  format `TechnologyPortName`, e.g. `HttpInventoryClient`, `PostgresOrderRepository`. Exception: Kafka producer/consumer
  adapters just get an `Impl` suffix instead (e.g. `PaymentKafkaProducerImpl`), since the port name already states the
  technology (`EntityKafkaProducer`/`EntityKafkaConsumer`), making a `Kafka`-prefixed adapter name redundant.
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
* In Go services, errors must live in an `errors.go` file, located next to the file (s) that use them.
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
* Mapper functions/methods must be named `fromDtoName`/`toDtoName`, e.g.
  `fromStripeStatus`, `toPaymentResponse`. The same convention applies to repository entity mappers, e.g.
  `fromPaymentEntity`, `toPaymentEntity`.
* In Go services, mappers (for both DTOs and repository entities) must be plain functions without receivers. In
  Java/Kotlin services, mappers must be their own classes in a `mapper` package, at the same level as the `dto`/`entity`
  package.
* DTOs for HTTP controllers must be named `EndpointNameRequest`/`EndpointNameResponse`, e.g. `PlaceOrderRequest`,
  `PlaceOrderResponse`.
* DTOs for Kafka must be named `MessageNameMessageType`, e.g. `CreateShipmentCommand`,
  `PaymentConfirmedEvent`.
* If a DTO is used for both the request and the response of the same resource, name it
  `NameDto` (e.g. `ProductDto`) instead of separate `Request`/`Response` names.
* If a DTO is composed of other DTOs, building those nested DTOs must be delegated to the mapper function/method
  responsible for mapping that specific nested DTO, rather than built inline by the outer mapper - each mapper stays the
  Information Expert for its own DTO.
* When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused
  mapping methods.
