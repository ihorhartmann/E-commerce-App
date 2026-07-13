# Payment Service Architecture

**Architecture:** Hexagonal

**Language:** Java

**Technologies:** SpringBoot, jOOQ

## Layers and Packages

### Domain

Contains domain objects that represent business concepts and rules.

This layer must not depend on any other layer.

Exceptions in this layer must be declared in a `domain.exception` package.

#### Package structure

```text
domain/
├── model/
└── values/
```

##### domain.model

Domain models with their own identity.

Examples:

* `Product`
* `Order`

##### domain.values

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
databases, kafka, third-party services.

Outbound ports are grouped by transport or technology type and must be named like `EntityRepository`, `EntityProducer`,
etc.

##### application.service

Services contain business logic that operates on domain objects.

Classes in this package provide implementations of the inbound service port interfaces defined in `application.port.in`
and must be named like `EntityServiceImpl`, for example `PaymentServiceImpl`.


---

### Adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Application` and `Domain` layers.

Exceptions used by this layer must be declared in the `Application` layer and located in the package of the
corresponding port interface.

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

Outbound adapters that implement interfaces defined in `application.port.out`. Output adapter implementations must be
named in format `TechnologyPortName`.

Examples:

* `PostgresShipmentRepository`

##### adapter.in

Inbound adapters that invoke application logic and serve as entry points to the service.

Examples:

* REST API controllers
* Kafka consumers

HTTP error handling must be declared in an `GlobalExceptionHandler` class located in the `adapter.in.http` package.

Controllers must be named like `EntityController`, for example `ShipmentController`.

#### DTO mapping rules

DTO packages are responsible for mapping between DTOs and domain models.

Mapping must be implemented using Mapper classes.

Method names must be:

* `fromDomainModel`
* `toDomainModel`

When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused
mapping methods.

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Database migrations
* Environment configuration
* Application configuration

This layer may depend only on the `Adapters` layer.
