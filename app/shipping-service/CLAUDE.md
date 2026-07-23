# 4. Shipping Service Architecture

**Architecture:** Hexagonal

**Language:** Go

**Technologies:** GORM, Gin, kafka-go


## Generic code conventions

* Use lowerCamelCase  identifiers by default.
* Use UpperCamelCase only for exported types, functions, methods, constants, and variables.
* If a struct intentionally implements an interface, the interface must be explicitly embedded in the struct.
* HTTP status codes must always be taken from the `net/http` package.


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
* `Order`

##### domain/values

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

Outbound ports are grouped by transport or technology type and must be named like `EntityRepository`, `EntityProducer`, etc.

##### application/services

Services contain business logic that operates on domain objects.

Classes in this package provide implementations of the inbound service port interfaces defined in `application/ports/in` and must be named like `EntityServiceImpl`, for example `ShipmentServiceImpl`.

---

### Adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Application` and `Domain` layers.

#### Package structure

```text
adapters/
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

##### adapters/common

Shared code used by both incoming and outgoing adapters.

Examples:

* Kafka event types
* Shared adapter utilities

Kafka adapters also use shared producer and consumer interfaces defined under the `common` package.

##### adapters/out

Outbound adapters that implement interfaces defined in `application/ports/out`. Output adapter implementations must be named in format `TechnologyPortName`.

Examples:

* `KafkaGoShipmentProducer`
* `PostgresShipmentRepository`

##### adapters/in

Inbound adapters that invoke application logic and serve as entry points to the service.

Examples:

* REST API handlers
* Kafka consumers

HTTP error handling must be declared in an `errors.go` file located next to the controller where it is used.

Controllers must be named like `EntityController`, for example `ShipmentController` and each controller must provide a `RegisterRoutes` function responsible for registering all controller routes.


### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* GORM
* Database migrations
* kafka-go
* Environment configuration
* Application configuration

This layer may depend only on the `Adapters` layer.

This layer contains the implementations of:

* `CommonKafkaProducer`
* `CommonKafkaConsumer`

These implementations do not contain business-specific logic and are therefore implemented at the infrastructure level.

Kafka producers and consumers that contain business-specific behavior belong to the `Adapters` layer.
