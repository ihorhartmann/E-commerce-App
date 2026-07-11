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

Errors in this layer must be declared in an `errors.go` file located next to the file where they are used.

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

Errors in this layer must be declared in an `errors.go` file located next to the file where they are used.

#### Package structure

```text
application/
├── ports/
├── services/
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

Example:

```text
out/
├── http/
├── kafka/
├── repository/
└── ...
```

##### application/services

Services contain business logic that operates on domain objects.

Service implementations must implement inbound ports and be named like `EntityServiceImpl`, for example `ShipmentServiceImpl`.

---

### Adapters

Contains adapters that connect the application core with external interfaces and infrastructure.

This layer may depend on the `Application` and `Domain` layers.

Errors used by adapter must be declared in an `errors.go` file located next to the corresponding port interface in the `Application` layer.

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

Outbound adapters that implement interfaces defined in `application/ports`. Output adapter implementations must be named in format `TechnologyPortName`.

Examples:

* `KafkaGoShipmentProducer`
* `PostgresShipmentRepository`
##### adapters/in

Inbound adapters that invoke application logic and serve as entry points to the service.

Examples:

* REST API handlers
* gRPC handlers
* Kafka consumers

HTTP error handling must be declared in an `errors.go` file located next to the controller where it is used.

Controllers must be named like `EntityController`, for example `ShipmentController` and each controller must provide a `RegisterRoutes` function responsible for registering all controller routes.

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

Mapping must be implemented using package-level functions without receivers. Nested DTOs must also have dedicated functions responsible for mapping nested objects.

Function names must be like `FromDtoName` and `ToDtoName`, for example `FromCreateOrderRequest`, `ToCreateOrderResponse`.

When introducing a new DTO, implement only the mapping functions required by the current service. Do not create unused mapping functions.

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
