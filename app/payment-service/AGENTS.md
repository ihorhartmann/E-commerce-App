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
├── ports/
├── services/
└── ...
```

The package contains:

* Services
* Ports

##### application/ports

Ports are interfaces that define how business logic interacts with external systems, such as:

* Databases
* Kafka
* Third-party services

##### application/services

Services contain business logic that operates on domain objects.


---

### Adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Application` and `Domain` layers.

#### Package structure

```text
adapters/
├── common/
├── in/
└── out/
```

##### adapters/common

Shared code used by both incoming and outgoing adapters.

Examples:

* Kafka event types
* Shared adapter utilities

Kafka event types are defined in:

```text
adapters/common/kafka
```

##### adapters/out

Outgoing adapters that implement interfaces defined in `usecases/ports`.

##### adapters/in

Incoming adapters that invoke use cases and serve as entry points to the service.

Examples:

* REST API handlers
* gRPC handlers
* Kafka consumers

#### Adapter organization

Within both `in` and `out`, adapters are grouped by transport or technology type.

Examples:

```text
in/
├── http/
├── grpc/
└── kafka/

out/
├── kafka/
├── grpc/
└── ...
```

Each adapter type contains dedicated DTO packages.

#### DTO mapping rules

DTO packages are responsible for mapping between DTOs and domain models.

Mapping must be implemented using Mapper classes.

Method names must be:

* `fromDomainModel`
* `toDomainModel`

When introducing a new DTO, implement only the mapping methods required by the current service. Do not create unused mapping methods.

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Database migrations
* Environment configuration
* Application configuration

This layer depends only on the `Adapters` layer.
