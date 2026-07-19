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

##### Port-owned DTOs and exceptions

This applies to both `application.port.in` and `application.port.out`. When a port needs DTOs or exceptions of its
own (e.g. a service's result shape, a gateway's request/response shape, or some exception), place
them in `dto` / `exception` subpackages next to that port interface, instead of next to the interface itself. This is
not needed for every port — only when a port actually owns DTOs or exceptions of its own. 

Examples:

```text
application/port/in/
├── PaymentService.java
└── dto/
    └── SubmitPaymentResult.java

application/port/out/http/
├── PaymentGateway.java
├── dto/
│   └── PaymentIntentResult.java
└── exception/
    └── PaymentGatewayException.java
```

A DTO belongs in a port's `dto` package only if it is actually referenced in that port interface's
own method signatures. A DTO used only by one adapter implementation (e.g. the exact JSON shape a
Kafka producer serializes, or an HTTP client's request/response body) is adapter-owned and must be
placed in that adapter's own `dto` subpackage instead, even if the DTO represents the same kind of
event the port's methods deal with.

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

* `fromDomain`
* `toDomain`

When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused
mapping methods.

#### Adapter-owned DTOs, entities and mappers

When an adapter package needs DTOs, database entities, and/or mappers of its own, place them in `dto` / `entity` /
`mapper` subpackages next to the class that uses them (controller, repository, etc.), instead of next to it directly.
`entity` is for adapter-side database entities (e.g. a JPA/jOOQ record distinct from the domain model) — used by
`adapter.out.repository` adapters, not by HTTP adapters. Database entities must never cross into the `Application`
layer; repository adapters convert between entity and domain model internally. This is not needed for every
adapter package — only when it actually owns DTOs, entities, or mappers of its own. Example:

```text
adapter/in/http/payment/
├── PaymentController.java
├── dto/
│   ├── InitiatePaymentRequest.java
│   └── PaymentResponse.java
└── mapper/
    └── PaymentMapper.java

adapter/out/repository/payment/
├── PostgresPaymentRepository.java
├── entity/
│   └── PaymentEntity.java
└── mapper/
    └── PaymentEntityMapper.java
```

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Database migrations
* Environment configuration
* Application configuration

This layer may depend only on the `Adapters` layer.
