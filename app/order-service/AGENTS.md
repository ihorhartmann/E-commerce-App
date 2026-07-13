# Order Service Architecture

**Architecture:** Onion

**Language:** Kotlin

**Technologies:** SpringBoot, Hibernate

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
│   │   ├── saga/
│   │   └── service/
│   │
│   └── out/
│       ├── repository/
│       ├── kafka/
│       ├── http/
│       └── ...
│
├── saga/
├── service/
└── ...
```

##### application/ports/in

Inbound ports are interfaces that define how business logic is invoked by `Infrastructure` layer.

It contains service and saga interfaces separated in different packages `saga` and `service`.

Inbound ports must be named like `EntityService` or `SagaNameSaga`, for example `ShipmentService` or `FulfillOrderSaga`.

##### application/ports/out

Outbound ports are interfaces that define how business logic interacts with external systems, such as:
databases, kafka, third-party services.

Outbound ports are grouped by transport or technology type and must be named like `EntityRepository`, `EntityProducer`,
etc.

##### application/saga

Contains saga orchestration logic for long-running business processes that span multiple services.

Classes in this package provide implementations of the inbound saga port interfaces defined in
`application/ports/in/saga`.

Sagas coordinate multiple application steps by:

* Executing a sequence of operations across services
* Tracking the current saga state
* Handling successful completion
* Triggering compensating actions when a step fails

##### application/services

Services contain business logic that operates on domain objects.

Classes in this package provide implementations of the inbound service port interfaces defined in
`application/ports/in/service`.

---

### Infrastructure

Contains implementations of ports from `Application` layer that integrate the application core with external systems and
infrastructure. Also includes framework-specific code, drivers, and related configuration. Examples:

* Spring configuration
* Database migrations
* Environment configuration
* Application configuration

Exceptions used by this layer must be declared in the `Application` layer and located in the package of the
corresponding port interface.

This layer may depend on the `Application` and `Domain` layers.

#### Package structure

Packages are grouped by technical responsibility or external technology integration.

```text
infrastructure/
├── config/
├── env/
├── http/
├── kafka/
└── repository/
```

#### DTO mapping rules

DTO packages are responsible for mapping between DTOs and domain models.

Mapping must be implemented using Mapper classes.

Method names must be:

* `fromDomainModel`
* `toDomainModel`

When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused
mapping methods.

### Interfaces

Contains entry points to the service and invoke the business logic in the `Application` layer.

This layer may depend on the `Application` and `Domain` layers.

HTTP error handling must be declared in an `GlobalExceptionHandler` class located in the `interfaces.http` package.

Controllers must be named like `EntityController`, for example `ShipmentController`.

#### Package structure

Packages are grouped by transport or technology type.

```text
interfaces/
├── http/
└── kafka/
```

#### DTO mapping rules

DTO packages are responsible for mapping between DTOs and domain models.

Mapping must be implemented using Mapper classes.

Method names must be:

* `fromDomainModel`
* `toDomainModel`

When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused
mapping methods.
