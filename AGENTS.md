# TODO start, run, shutdown
всі сервіси отримують необхідні змінні для підключення до кафки та бази даних через env vars.
Якщо змінної немає - сервіс не стартує і виводить помилку в лог.

Всі сервіси мають мати graceful shutdown, щоб при отриманні сигналу завершення роботи сервіс міг закрити всі підключення до кафки та бази даних.

Всі сервіси мають мати healthcheck endpoint, який повертає статус сервісу




# Overall Architecture

The system consists of four services:

* Inventory
* Order
* Payment
* Shipping

Services communicate with each other through Kafka and expose REST APIs for frontend communication.

All services are located in the `app` directory.

The **Order Service** acts as the orchestrator for the other services.

## Services

1. **Inventory**

  * Architecture: Clean
  * Language: Go
  * Technologies: GORM, Fiber, kafka-go

2. **Order**

  * Architecture: Onion
  * Language: Kotlin
  * Technologies: Spring Boot, Hibernate

3. **Payment**

  * Architecture: Hexagonal
  * Language: Java
  * Technologies: Spring Boot, jOOQ

4. **Shipping**

  * Architecture: Hexagonal
  * Language: Go
  * Technologies: GORM, Gin, kafka-go

# 1. Inventory Service Architecture

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


# 3. Payment Service Architecture

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

##### application/services

Services contain business logic that operates on domain objects.

##### application/ports

Ports are interfaces that define how business logic interacts with external systems, such as:

* Databases
* Kafka
* Third-party services

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

Kafka adapters also use shared producer and consumer interfaces defined under the `common` package.

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

Mapping must be implemented using package-level functions without receivers.

Function names must be:

* `FromDomainModel`
* `ToDomainModel`

When introducing a new DTO, implement only the mapping functions required by the current use case. Do not create unused mapping functions.

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* GORM
* Database migrations
* kafka-go
* Environment configuration
* Application configuration

This layer depends only on the `Adapters` layer.

This layer contains the implementations of:

* `CommonKafkaProducer`
* `CommonKafkaConsumer`

These implementations do not contain business-specific logic and are therefore implemented at the infrastructure level.

Kafka producers and consumers that contain business-specific behavior belong to the `Adapters` layer.


# 4. Shipping Service Architecture

**Architecture:** Hexagonal

**Language:** Go

**Technologies:** GORM, Gin, kafka-go


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

##### application/services

Services contain business logic that operates on domain objects.

##### application/ports

Ports are interfaces that define how business logic interacts with external systems, such as:

* Databases
* Kafka
* Third-party services

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

Kafka adapters also use shared producer and consumer interfaces defined under the `common` package.

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

Mapping must be implemented using package-level functions without receivers.

Function names must be:

* `FromDomainModel`
* `ToDomainModel`

When introducing a new DTO, implement only the mapping functions required by the current use case. Do not create unused mapping functions.

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* GORM
* Database migrations
* kafka-go
* Environment configuration
* Application configuration

This layer depends only on the `Adapters` layer.

This layer contains the implementations of:

* `CommonKafkaProducer`
* `CommonKafkaConsumer`

These implementations do not contain business-specific logic and are therefore implemented at the infrastructure level.

Kafka producers and consumers that contain business-specific behavior belong to the `Adapters` layer.
