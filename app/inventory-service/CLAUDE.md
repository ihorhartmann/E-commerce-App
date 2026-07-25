# Inventory Service Architecture

**Architecture:** Onion

**Language:** Go

**Technologies:** GORM, Fiber, kafka-go

## Generic code conventions

* Use lowerCamelCase identifiers by default.
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
├── ports/
│   ├── common/
│   │   └── kafka/
│   ├── in/
│   └── out/
│       ├── kafka/
│       │   └── common/
│       └── repository/
└── ...
```

##### application/ports/in

Inbound ports are interfaces that define how business logic is invoked by the `Interfaces` layer.

Inbound ports must be named like `EntityService`, for example `ProductService` or `ReservationService`.

##### application/ports/out

Outbound ports are interfaces that define how business logic interacts with external systems, such as:
databases, kafka, third-party services.

Outbound ports are grouped by transport or technology type and must be named like `EntityRepository`,
`EntityKafkaProducer`, etc. Ports declared here are implemented exclusively by outbound adapters in the
`Infrastructure` layer.

##### application/ports/common

Code shared between an `Infrastructure` adapter and an `Interfaces` adapter (not limited to constants - e.g. Kafka topic
names, header names, message-type names, or other shared types and utilities) must not live inside either layer's own
package, since `Infrastructure` and `Interfaces` are siblings and neither may depend on the other. Put it in a dedicated
package under `application/ports/common` instead, since both layers are already allowed to depend on `Application`.

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

Outbound adapter implementations that implement the ports declared in `application/ports/out` must be named in format
`TechnologyPortName`.

Examples:

* `PostgresProductRepository`

`env`, `gorm`, and `kafkago` contain framework- and driver-level code (environment configuration, GORM setup and
migrations, the raw kafka-go client wrapper) that contains no business-specific logic.

---

### Interfaces

Contains entry points to the service and invoke the business logic in the `Application` layer.

This layer may depend on the `Application` and `Domain` layers.

HTTP error handling must be declared in an `errors.go` file located next to the controller where it is used.

Controllers must be named like `EntityController`, for example `ProductController`, and each controller must provide a
`RegisterRoutes` function responsible for registering all controller routes. Kafka consumers must be named like
`EntityKafkaConsumer`, for example `ReservationKafkaConsumer`.

#### Package structure

Packages are grouped by transport or technology type.

```text
interfaces/
├── http/
│   ├── dto/
│   ├── errors/
│   └── middleware/
└── kafka/
    ├── common/
    └── dto/
```
