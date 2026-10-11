# Order Saga

Order Service is the orchestrator of the order placement saga. This document
describes the full path of an order from checkout to delivery: who talks to
whom and how (HTTP or Kafka), and what happens on failure at each step.

## Legend

```mermaid
sequenceDiagram
    participant A
    participant B
    A->>B: solid arrow: synchronous HTTP call
    B-->>A: solid arrow (dashed head): its response
    A--)B: dashed arrow: asynchronous Kafka message
```

An `alt / else` block in a diagram marks a failure branch and its compensation.

## Order states

`OrderStatus` covers five broad values - the detailed saga progress
(payment confirmed, picked up, returned to inventory, etc.) isn't tracked as
separate statuses but as nullable facts on `Order` (`paymentConfirmedAt`,
`shipment.pickedUpAt`, `shipment.deliveredAt`, `refund.confirmedAt`,
`refund.failedAt`, `shipment.returnedAt`) plus an `awaitingReturn` flag.

```mermaid
stateDiagram-v2
    [*] --> PLACED: place order

    PLACED --> CANCELLED: out of stock
    PLACED --> CANCELLED: reservation error
    PLACED --> CANCELLED: payment failed
    PLACED --> PROCESSING: payment confirmed

    PROCESSING --> CANCEL_REQUESTED: shipment creation failed
    PROCESSING --> CANCEL_REQUESTED: shipment lost
    PROCESSING --> CANCEL_REQUESTED: shipment cancelled
    PROCESSING --> FULFILLED: delivered

    CANCEL_REQUESTED --> CANCEL_REQUESTED: refund confirmed,<br/>awaiting return
    CANCEL_REQUESTED --> CANCEL_REQUESTED: returned,<br/>awaiting refund
    CANCEL_REQUESTED --> CANCEL_REQUESTED: refund failed
    CANCEL_REQUESTED --> CANCELLED: refund & return done

    CANCELLED --> [*]
    FULFILLED --> [*]
```

Labels above are kept short for rendering; exact `cancellationReason` values
for each `-> CANCELLED` / `-> CANCEL_REQUESTED` trigger are listed in
"Compensation steps" below.

`RefundFailed` deliberately doesn't move the status - it's a flag for support
(`refund.failedAt`), not a separate stage: without manual intervention
(re-requesting the refund) the order simply stays in `CANCEL_REQUESTED`. This
is intentional: staying in `CANCEL_REQUESTED` instead of moving to some
dead-end status keeps the order visible in whatever view tracks "still needs
to be resolved" - so an order with a failed refund can't quietly fall through
the cracks and get forgotten.

## Protocol: checkout → payment → shipment creation

```mermaid
sequenceDiagram
    autonumber
    actor U as Frontend
    participant O as Order Service
    participant I as Inventory Service
    participant P as Payment Service
    participant S as Stripe
    participant Sh as Shipping Service

    U->>O: HTTP: POST /api/v1/orders (products and quantities, no prices)
    Note over O: takes the next order id - nothing is stored yet
    O->>I: HTTP: POST /api/v1/reservations

    alt Out of stock (InsufficientStockException)
        I-->>O: HTTP: 409 insufficient stock
        O-->>U: HTTP: 409 error
        Note over O: no order is created
    else Other reservation error
        I-->>O: HTTP: error
        O-->>U: HTTP: error
        Note over O: no order is created
    else Reservation OK
        I-->>O: HTTP: 201 reserved, with the price of every product
        Note over O: Order.createNew(id, requestedItems, prices) -> PLACED, stored
        O->>P: HTTP: POST /api/v1/payments

        alt Payment service unavailable / crashed
            Note over O: Order.failPayment() -> CANCELLED, reason="Payment failed"
            O--)I: Kafka: CancelReservation
            O-->>U: HTTP: error
        else Payment service responded
            P->>S: HTTP: create PaymentIntent
            S-->>P: HTTP: client_secret
            P-->>O: HTTP: client_secret
            O-->>U: HTTP: 201 {order, client_secret}

            U->>S: HTTP: confirmCardPayment (directly, bypassing the backend)
            S->>P: HTTP: webhook POST /webhooks/stripe

            alt Payment declined
                P--)O: Kafka: PaymentFailed
                Note over O: Order.failPayment() -> CANCELLED, reason="Payment failed"
                O--)I: Kafka: CancelReservation
            else Payment succeeded
                P--)O: Kafka: PaymentCompleted
                Note over O: Order.confirmPayment() -> PROCESSING, paymentConfirmedAt
                O--)Sh: Kafka: CreateShipment
                Note over Sh: on failure, retried by Shipping's own consumer<br/>(no event back to Order yet - DLQ handling is TODO)

                Sh--)O: Kafka: ShipmentCreated
                Note over O: Order.createShipment(shipmentId)<br/>(stays PROCESSING)
                O--)I: Kafka: HoldReservation
                Note over I: reservation holder -> Courier
            end
        end
    end
```

## Protocol: delivery → outcome (Delivered / Lost / Cancelled)

Continues from the point where the `Shipment` has already been created
(status `Created`, `Order` in `PROCESSING`). Courier actions
(`pickup`/`deliver`/`lose`/`cancel`/`return`) are HTTP calls made directly to
the Shipping Service, not through Order.

```mermaid
sequenceDiagram
    autonumber
    actor C as Courier
    participant Sh as Shipping Service
    participant O as Order Service
    participant I as Inventory Service
    participant P as Payment Service

    C->>Sh: HTTP: POST /shipments/{id}/pickup
    Sh--)O: Kafka: ShipmentInDelivery
    Note over O: Order.startDelivery() -- shipment.pickedUpAt<br/>(stays PROCESSING)
    O--)I: Kafka: FulfillReservation
    Note over I: onHand -= qty, reservation deleted

    alt Delivered successfully
        C->>Sh: HTTP: POST /shipments/{id}/deliver
        Sh--)O: Kafka: ShipmentDelivered
        Note over O: Order.deliver() -> FULFILLED, deliveredAt

    else Shipment lost in transit
        C->>Sh: HTTP: POST /shipments/{id}/lose
        Sh--)O: Kafka: ShipmentLost
        Note over O: Order.loseShipment().requestRefund()<br/>-> CANCEL_REQUESTED, reason="Shipment lost"
        O--)P: Kafka: RefundRequested

    else Customer cancelled (before or after pickup)
        C->>Sh: HTTP: POST /shipments/{id}/cancel
        Sh--)O: Kafka: ShipmentCancelled
        Note over O: Order.cancelShipment().requestRefund()<br/>-> CANCEL_REQUESTED, reason="Cancelled by customer"<br/>awaitingReturn = whether pickup already happened
        opt Not picked up yet
            O--)I: Kafka: CancelReservation
        end
        O--)P: Kafka: RefundRequested

        opt Already picked up (awaitingReturn=true) - courier physically returns the goods
            C->>Sh: HTTP: POST /shipments/{id}/return
            Sh--)O: Kafka: ShipmentReturnedToInventory
            Note over O: Order.returnToStock() -- shipment.returnedAt<br/>-> CANCELLED only if refund already confirmed,<br/>otherwise stays CANCEL_REQUESTED
            O--)I: Kafka: Restock
            Note over I: onHand += qty (per order item)
        end
    end

    alt Refund confirmed by Stripe
        P--)O: Kafka: RefundCompleted
        Note over O: Order.confirmRefund(amount) -- refund.confirmedAt/amount<br/>-> CANCELLED unless still awaitingReturn,<br/>otherwise stays CANCEL_REQUESTED
    else Refund failed
        P--)O: Kafka: RefundFailed
        Note over O: Order.markRefundFailed() -- refund.failedAt,<br/>stays CANCEL_REQUESTED (needs manual intervention)
    end
```

`RefundCompleted`/`RefundFailed` and `ShipmentReturnedToInventory` are two
independent events that can arrive in either order (refund confirmation is
typically much faster than the courier's physical trip back).
`Order.confirmRefund`/`Order.returnToStock` each check whether the OTHER half
has already completed before finalizing to `CANCELLED` - that's exactly what
`awaitingReturn` is for.

## Compensation steps - quick summary

**Out of stock.** The cheapest failure point - before the order is stored, before
the PaymentIntent is created and before any charge. Just return an error to the
frontend: no order exists, so there is nothing to compensate.

**Payment service unavailable or crashed.** The reservation already exists,
the `POST /api/v1/payments` request threw before Stripe ever responded. The
compensation is the same action: `CancelReservation` in Inventory.

**Payment declined (Stripe).** The reservation already exists, no money was
charged. The compensation is one action: `CancelReservation` in Inventory.

**Shipment creation failed.** Money was already charged, the reservation is
still held. The compensation is threefold: cancel the reservation in
Inventory, request a refund from Payment, and wait for its confirmation - all
through Kafka.

**Shipment cancelled BEFORE pickup.** The reservation hasn't been fulfilled
yet - the compensation mirrors the previous case: `CancelReservation` +
`RefundRequested`.

**Shipment cancelled AFTER pickup.** Stock has already been physically
decremented (`FulfillReservation` already ran) - `CancelReservation` makes no
sense here. Instead, the order waits for TWO independent events: refund
confirmation and the courier physically returning the goods (`Restock` in
Inventory). Only once both have happened does the order become `CANCELLED`.

**Shipment lost after pickup.** The goods are never coming back
(`awaitingReturn` is always `false` on this path) - the only compensation is
`RefundRequested` via Kafka to Payment.

**Refund failed at Stripe.** `RefundFailed` only flags `refund.failedAt` -
the order stays in `CANCEL_REQUESTED` with no automatic retry; it needs
manual intervention (support / re-requesting the refund).

## Kafka topics

One topic per producing domain + message kind (command/event). Message ordering for a given aggregate (order /
shipment) is guaranteed by the partitioning key (`orderId`), not
by splitting into topics.

| Topic | Producer | Consumer | Messages |
|---|---|---|---|
| `inventory.commands` | Order | Inventory | `CancelReservation`, `FulfillReservation`, `HoldReservation`, `Restock` |
| `shipping.commands` | Order | Shipping | `CreateShipment` |
| `payment.commands` | Order | Payment | `RefundRequested` |
| `payment.events` | Payment | Order | `PaymentCompleted`, `PaymentFailed`, `RefundCompleted`, `RefundFailed` |
| `shipping.events` | Shipping | Order | `ShipmentCreated`, `ShipmentInDelivery`, `ShipmentDelivered`, `ShipmentCancelled`, `ShipmentLost`, `ShipmentReturnedToInventory` |

Commands (`*.commands`) are an order to a specific service to do one targeted
thing. Events (`*.events`) are a fact that happened. Every message carries
an `eventType`, `correlationId`, `eventId` envelope on top of the JSON
payload, so a consumer on a topic with multiple message types can correctly
recognize what just arrived.
