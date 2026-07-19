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


# Code principles
The Domain/Entity layers for each service must follow the Information Expert principle. A class/struct in the domain must know everything relevant to it - no more, no less.


# Start, shutdown
All services receive the environment variables required to connect to Kafka and the database via env vars.
If a variable is missing, the service must not start and must log an error.

All services must have graceful shutdown, so that on receiving a termination signal the service can close all Kafka and database connections.


# Exceptions, errors, and DTOs

* In Java/Kotlin services, exceptions must live in a separate `exception` package,
  located next to the file(s) that use them.
* DTOs (Java/Kotlin/Go) must live in a separate `dto` package, located next to the
  file(s) that use them.
* In Go services, errors must live in an `errors.go` file, located next to the
  file(s) that use them.
* Exceptions, errors, and DTOs must be declared at the outermost architectural layer
  possible:
  * if a DTO/exception is needed in the signature of an inner layer's port (interface) -
    declare it next to that port;
  * if it's only needed by a specific implementation of that port in an outer layer
    (e.g. an adapter) - declare it next to that adapter, not next to the port.
