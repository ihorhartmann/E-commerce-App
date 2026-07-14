# Inventory Service Architecture

**Architecture:** Clean

**Language:** Go

**Technologies:** GORM, Fiber, kafka-go

## Generic code conventions

* Use lowerCamelCase  identifiers by default.
* Use UpperCamelCase only for exported types, functions, methods, constants, and variables.
* If a struct intentionally implements an interface, the interface must be explicitly embedded in the struct.
* HTTP status codes must always be taken from the `net/http` package.


## Layers and Packages

### Entities

Contains domain objects that represent business concepts and rules.

This layer must not depend on any other layer.

Errors in this layer must be declared in an `errors.go` file located next to the file where they are used.

#### Package structure

```text
entities/
├── model/
└── values/
```

##### entities/model

Domain models with their own identity.

Examples:

* `Product`
* `Order`

##### entities/values

Value objects used by domain models.

Value objects do not have their own identity.

Example:

* `Money`

---

### Use cases

Contains application business logic and ports that define how business logic interacts with external systems.

This layer depends only on the `Entities` layer.

Errors in this layer must be declared in an `errors.go` file located next to the file where they are used.

#### Package structure

```text
usecases/
├── ports/
│   ├── kafka/
│   └── repository/
└── ...
```

The package contains:

* Use cases
* Ports

##### usecases/ports

Outbound ports are interfaces that define how business logic interacts with external systems, such as:
databases, kafka, third-party services.

Outbound ports are grouped by transport or technology type and must be named like `EntityRepository`, `EntityProducer`, etc.

---

### Interface adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Use Cases` and `Entities` layers.

Errors used by adapter must be declared in an `errors.go` file located next to the corresponding port interface in the `Application` layer.

#### Package structure

```text
interfaceadapters/
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

##### interfaceadapters/common

Shared code used by both incoming and outgoing adapters.

Examples:

* Kafka event types
* Shared adapter utilities

Kafka adapters also use shared producer and consumer interfaces defined under the `common` package.

##### interfaceadapters/out

Outbound adapters that implement interfaces defined in `usecases/ports/out`. Output adapter implementations must be named in format `TechnologyPortName`.

Examples:

* `KafkaGoShipmentProducer`
* `PostgresShipmentRepository`


##### interfaceadapters/in

Inbound adapters that invoke application logic and serve as entry points to the service.

Examples:

* REST API controllers
* Kafka consumers

HTTP error handling must be declared in an `errors.go` file located next to the controller where it is used.

Controllers must be named like `EntityController`, for example `ShipmentController` and each controller must provide a `RegisterRoutes` function responsible for registering all controller routes.


#### DTO mapping rules

DTO packages are responsible for mapping between DTOs and domain models.

Each adapter type contains dedicated DTO packages.

Mapping must be implemented using package-level functions without receivers. Nested DTOs must also have dedicated functions responsible for mapping nested objects.

Function names must be like `FromDtoName` and `ToDtoName`, for example `FromCreateOrderRequest`, `ToCreateOrderResponse`.

When introducing a new DTO, implement only the mapping functions required by the current service. Do not create unused mapping functions.

### Frameworks

Contains frameworks, drivers, and their configuration.

Examples:

* GORM
* Database migrations
* kafka-go
* Environment configuration
* Application configuration

This layer may depend only on the `Interface adapters` layer.

This layer contains the implementations of:

* `CommonKafkaProducer`
* `CommonKafkaConsumer`

These implementations do not contain business-specific logic and are therefore implemented at the framework level.

Kafka producers and consumers that contain business-specific behavior belong to the `interfaceadapters` layer.
