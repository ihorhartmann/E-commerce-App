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
├── exception/
├── model/
│   └── exception/
└── value/
    └── exception/
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

##### domain.exception

Only the base of every exception this service raises on purpose - `PaymentServiceException`, its kinds and
`ErrorCode`; see [Error handling](#error-handling). The exceptions the domain objects themselves raise live next to
them, with their codes: `domain.model.exception` (`PaymentErrorCode`, `RefundErrorCode`, e.g.
`PaymentNotRefundableException`) and `domain.value.exception` (`ValueErrorCode`, e.g. `InvalidMoneyAmountException`).

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
│       │   └── exception/
│       ├── kafka/
│       ├── http/
│       │   └── exception/
│       └── ...
│
├── service/
│   └── exception/
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
├── common/
│   └── http/
│       └── stripe/
│           └── exception/
├── in/
│   ├── http/
│   │   ├── exception/
│   │   ├── idempotency/
│   │   ├── security/
│   │   └── webhook/
│   │       └── exception/
│   ├── kafka/
│   │   └── exception/
│   └── scheduled/
│
└── out/
    ├── kafka/
    ├── http/
    ├── repository/
    │   ├── entity/
    │   └── exception/
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

`adapter.in.http.idempotency` holds `HttpIdempotencyService`, which decides whether a mutating HTTP request should
proceed, replay a cached response, or be rejected, for `IdempotencyFilter`. It composes
`application.port.out.idempotency.HttpRequestStore` (a persistence port for idempotent_http_requests rows,
implemented by `adapter.out.idempotency`) but owns all of the actual decision logic itself - the store never
branches on what a conflict or a stale record means. `HttpIdempotencyService` lives here, not in `Application`,
because its whole purpose is specific to the HTTP idempotency-key pattern (an `Adapters`-owned concern), not to
orchestrating domain behavior.

`adapter.in.scheduled` holds periodic sweeps (e.g. `PaymentExpirationSweep`, `HttpRequestSweep`)
that run on a timer. A scheduled job is a self-triggered entry point - it decides on its own schedule to invoke a
business port, the same role a Kafka consumer or REST controller plays for its own trigger - so it belongs in
`Adapters`, not `Infrastructure`, even though nothing here is framework-specific glue.

### Infrastructure

Contains frameworks, drivers, and their configuration.

Examples:

* Spring configuration
* Database migrations
* Environment configuration
* Application configuration

This layer may depend only on the `Adapters` layer. In rare cases, configuration that wires beans together may also
reference the `Application` layer directly - e.g. a `@Bean` method that must return an outbound port interface.

#### Package structure

Packages are grouped by technical responsibility or external technology integration.

```text
infrastructure/
├── config/
├── kafka/
├── observation/
└── security/
```

`infrastructure.config` holds only `@Configuration` classes. The framework glue they wire in lives next to them, in
a package per framework: `infrastructure.kafka` (e.g. `RetryableDeadLetterPublishingRecoverer`),
`infrastructure.observation` (the span name conventions, e.g. `KafkaListenerSpanNameConvention`),
`infrastructure.security` (e.g. `RolesClaimGrantedAuthoritiesConverter`). A class that turns a framework event into
one of this service's own types is an adapter, not glue, even if a framework calls it: Spring Security's entry point
and access-denied handler, which turn a rejected request into this service's exceptions, live in
`adapter.in.http.security`.

## Error handling

Every exception this service raises on purpose extends `domain.exception.PaymentServiceException` through one of its
kinds - `InvalidException`, `NotFoundException`, `ConflictException`, `TransientException`, `UnavailableException` -
or directly, for an internal failure. A concrete exception passes its code, a constant internal message and its data
as attributes in its constructor:

```java
public class PaymentNotRefundableException extends ConflictException {
    public PaymentNotRefundableException(long paymentId, PaymentStatus status) {
        super(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE, "Payment can't be refunded",
                Map.of("paymentId", paymentId, "status", status));
    }
}
```

An exception thrown from several places with different data gets a static factory per place instead of a public
constructor (`PaymentNotFoundException.withOrderId(orderId)`, `ConcurrentUpdateException.ofRefund(refundId, version)`),
so each attribute key is still written once.

* The code (`PAYMENT.PAYMENT_NOT_FOUND` - `ErrorCode` adds the service prefix) and its title are public and the same
  for every occurrence; each layer declares its codes as an enum implementing `ErrorCode`, next to the exceptions that
  use them (`PaymentErrorCode`, `RepositoryErrorCode`, `GatewayErrorCode`, ...). Keep codes specific
  (`PAYMENT_NOT_FOUND`, not `NOT_FOUND`). The kind alone decides the HTTP status, whether a Kafka message is retried or
  dead-lettered at once (`isPermanent()`: invalid, not found, conflict), and whether `IdempotencyFilter` caches the
  response or releases the key.
* The internal message and the attributes never leave the service; keep the internal message constant and put every
  value in the attributes. The external message is the only per-occurrence text a caller sees.
* An exception gets its code where it first enters this service's code: a domain rule, `PostgresPaymentRepository`
  turning a jOOQ exception into `DatabaseFailureException`, `StripePaymentGateway` turning a Stripe exception into
  `PaymentGatewayRejectedException`. An exception that already is one of ours is rethrown as it is; wrapping it as the
  cause of another one replaces its code - do that only on purpose (e.g. `InconsistentPaymentException`).
* A failure that is this service's own fault is internal, even if a third party reports it: Stripe refusing the API key
  (`AuthenticationException`, and its subclass `PermissionException`) is `PaymentGatewayAuthenticationException`, not a rejection of
  the request - a refund is retried and dead-lettered for a redrive once the key is fixed, not dropped as permanent.
* A caught exception is never dropped: it becomes the cause of the exception thrown instead, or - when it must not
  decide how the new one is reported - is added to it with `addSuppressed`.
* An exception lives where the root CLAUDE.md puts exceptions: next to a port only if the port's callers need it
  (`PaymentNotFoundException`, `ConcurrentUpdateException`, `PaymentGatewayRejectedException` - the services catch it
  to fail the payment or the refund), otherwise next to the code that throws it (`adapter.out.repository.exception`,
  `application.service.exception`).
* The domain throws no `IllegalArgumentException`: every rule it enforces throws an exception of ours, and which one
  depends on who can break the rule, not on the rule:
    * a request or a message - an `InvalidException`/`ConflictException` (e.g. `InvalidPaymentOrderIdException`);
    * stored data - `InconsistentPaymentException`/`InconsistentRefundException` (internal), thrown by
      `createExisting` and by `PaymentEntity.toPayment`/`RefundEntity.toRefund` for a value the stored data fails to
      build;
    * only this service's own code, or the gateway's answer it passes on - a bug -
      `PaymentInvariantViolatedException`/`RefundInvariantViolatedException` (internal), e.g. releasing a refund of
      another payment.

  The two internal codes are kept apart, so a log tells whether to look at the data or at the code.
* Log an exception once, where it is handled - `GlobalExceptionHandler`, `PaymentKafkaConsumer`,
  `StripeWebhookController` - with every attribute of `Attributes.attributesOf(exception)` as a key-value pair of its
  own.

HTTP error responses are RFC 9457 problem details (`type` `urn:ecommerce:error:<code>`, `title`, `status`, `detail` =
the external message, `instance`, and the members `code` and `retryable`), written only by `GlobalExceptionHandler`.
Spring MVC calls it for the exceptions of the controllers; `IdempotencyFilter` and Spring Security's
`ProblemAuthenticationEntryPoint`/`ProblemAccessDeniedHandler` hand theirs to it through the `handlerExceptionResolver`
bean. A status that differs from the kind's default (401, 403, 409 for a request in progress, 422) is listed in its
`statusOf`. A transient or unavailable failure also gets `Retry-After`.

Kafka: `PaymentKafkaConsumer` rethrows every failure as it is, and `KafkaErrorHandlerConfig` gives a permanent one
(`KafkaFailures.isPermanentFailure`) no retries, so it is dead-lettered at once; any other failure is retried.

The Stripe webhook acknowledges an event that fails permanently (an unknown PaymentIntent, a status change the payment
rejects): Stripe would only redeliver it to fail the same way. Any other failure answers with a 5xx, so Stripe
redelivers the event later.