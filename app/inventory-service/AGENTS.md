# Inventory Service Architecture

**Architecture:** Clean

**Language:** Go

**Technologies:** GORM, Fiber, kafka-go

## Layers and Packages

### Entities

Contains domain objects that represent business concepts and rules.

This layer must not depend on any other layer.

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

Contains application business logic that operates on domain objects.

This layer depends only on the `Entities` layer.

#### Package structure

```text
usecases/
├── ports/
└── ...
```

The package contains:

* Use cases
* Ports

##### usecases/ports

Ports are interfaces that define how business logic interacts with external systems, such as:

* Databases
* Kafka
* Third-party services

---

### Interface adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Use cases` and `Entities` layers.

#### Package structure

```text
interfaceadapters/
├── common/
├── in/
└── out/
```

##### interfaceadapters/common

Shared code used by both incoming and outgoing adapters.

Examples:

* Kafka event types
* Shared adapter utilities

Kafka event types are defined in:

```text
interfaceadapters/common/kafka
```

Kafka adapters also use shared producer and consumer interfaces defined under the `common` package.

##### interfaceadapters/out

Outgoing adapters that implement interfaces defined in `usecases/ports`.

##### interfaceadapters/in

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

Mapping must be implemented using package-level functions without receivers.

Function names must be:

* `FromDomainModel`
* `ToDomainModel`

When introducing a new DTO, implement only the mapping functions required by the current use case. Do not create unused mapping functions.

### Frameworks

Contains frameworks, drivers, and their configuration.

Examples:

* GORM
* Database migrations
* kafka-go
* Environment configuration
* Application configuration

This layer depends only on the `Interface adapters` layer.

This layer contains the implementations of:

* `CommonKafkaProducer`
* `CommonKafkaConsumer`

These implementations do not contain business-specific logic and are therefore implemented at the framework level.

Kafka producers and consumers that contain business-specific behavior belong to the `interfaceadapters` layer.
