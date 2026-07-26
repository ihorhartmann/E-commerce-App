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