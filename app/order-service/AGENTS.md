# Order Service Architecture

**Architecture:** Onion

**Language:** Kotlin

**Technologies:** SpringBoot, Hibernate


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
├── saga/
├── service/
└── ...
```

##### application/ports

Ports are interfaces that define how business logic interacts with external systems, such as:

* Databases
* Kafka
* Third-party services

##### application/saga
Contains saga orchestration logic for long-running business processes that span multiple services.

Sagas coordinate multiple application steps by:

* Executing a sequence of operations across services
* Tracking the current saga state
* Handling successful completion
* Triggering compensating actions when a step fails

##### application/services

Services contain business logic that operates on domain objects.


---

### Infrastructure

Contains implementations of interfaces in `Application` layer that connect the application core with external infrastructure. And also contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Database migrations
* Environment configuration
* Application configuration

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

When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused mapping functions.

### Interfaces

Contains entry points to the service and invoke the business logic in the `Application` layer.

This layer may depend on the `Application` and `Domain` layers.

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

When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused mapping methods.
