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

    PLACED --> CANCELLED: payment failed
    PLACED --> PROCESSING: payment confirmed

    PROCESSING --> CANCEL_REQUESTED: shipment creation failed
    PROCESSING --> CANCEL_REQUESTED: customer asked to cancel
    PROCESSING --> CANCEL_REQUESTED: shipment lost
    PROCESSING --> CANCEL_REQUESTED: shipment cancelled by the courier
    PROCESSING --> FULFILLED: delivered

    CANCEL_REQUESTED --> FULFILLED: delivered before<br/>the request reached shipping
    CANCEL_REQUESTED --> CANCEL_REQUESTED: shipment cancelled or lost,<br/>refund requested
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

`CANCEL_REQUESTED` covers two phases, told apart by whether a refund was
requested (`refund`):

1. **awaiting shipping** - the customer asked to cancel, and Shipping hasn't
   answered yet. Nothing is refunded or released, and the shipment's events
   are still accepted (see "Customer cancels the order" below);
2. **cancellation confirmed** - the shipment is cancelled or lost, the refund
   is requested, and the order waits for it and, after a pickup, for the
   goods to come back.

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
        I-->>O: HTTP: 201 reserved, with the name, quantity and price of every line
        Note over O: Order.createNew(id, reservedItems) -> PLACED, stored
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
the Shipping Service, not through Order, and only the courier who picked the
shipment up may make any of them after the pickup. A courier's `cancel` means
the goods couldn't be handed over, and carries the reason: `RecipientRefused`
or `RecipientUnavailable` (nobody there, wrong address). The customer cancels
the order through Order instead - see the next protocol.

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

    else Goods couldn't be handed over
        C->>Sh: HTTP: POST /shipments/{id}/cancel {reason}
        Note over Sh: In Delivery -> Cancelled, reason as given,<br/>goods stay with the courier
        Sh--)O: Kafka: ShipmentCancelled {reason}
        Note over O: Order.cancelShipment(reason)<br/>-> CANCEL_REQUESTED, awaitingReturn=true, refund requested<br/>RecipientRefused -> reason="Cancelled by customer"<br/>RecipientUnavailable -> reason="Delivery failed"
        O--)P: Kafka: RefundRequested

        C->>Sh: HTTP: POST /shipments/{id}/return
        Sh--)O: Kafka: ShipmentReturnedToInventory
        Note over O: Order.returnToStock() -- shipment.returnedAt<br/>-> CANCELLED only if refund already confirmed,<br/>otherwise stays CANCEL_REQUESTED
        O--)I: Kafka: Restock
        Note over I: onHand += qty (per order item)
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

## Protocol: customer cancels the order

The customer cancels the **order**, through Order - never the shipment. Only a
paid order whose shipment hasn't ended can be cancelled (`PROCESSING`); an
unpaid one (`PLACED`) simply expires, and a `FULFILLED` or `CANCELLED` one is
rejected with `409`. Asking again while a cancellation is under way changes
nothing.

Order only **asks**: Shipping owns the shipment and decides whether it can
still be cancelled. So the request moves the order to `CANCEL_REQUESTED`
without refunding anything, and the refund starts only once Shipping confirms.

```mermaid
sequenceDiagram
    autonumber
    actor U as Frontend
    participant O as Order Service
    participant Sh as Shipping Service
    participant I as Inventory Service
    participant P as Payment Service

    U->>O: HTTP: POST /api/v1/orders/{id}/cancel
    Note over O: Order.requestCancellation()<br/>-> CANCEL_REQUESTED, reason="Cancelled by customer"<br/>no refund yet
    O--)Sh: Kafka: CancelShipment {orderId}
    O-->>U: HTTP: 200 order

    alt Shipment still open (Created or In Delivery)
        Note over Sh: -> Cancelled, reason=OrderCancelled,<br/>goods stay where they are
        Sh--)O: Kafka: ShipmentCancelled {reason}
        Note over O: Order.cancelShipment(reason) -- refund requested<br/>awaitingReturn = whether pickup already happened
        opt Not picked up yet
            O--)I: Kafka: CancelReservation
        end
        O--)P: Kafka: RefundRequested
        opt Already picked up - courier physically returns the goods
            Sh--)O: Kafka: ShipmentReturnedToInventory
            O--)I: Kafka: Restock
        end
    else Shipment already ended (Delivered, Lost, or Cancelled by the courier)
        Note over Sh: nothing left to cancel: the command is logged and dropped,<br/>no error, no event
        Note over O: the event of what happened first is already on its way
    end
```

The refund and the return then finish as in the previous protocol.

**The command can come too late.** The customer and the courier act
independently, and messages between Order and Shipping take time, so an event
about what the courier did *before* Shipping handled `CancelShipment` can
reach an order that is already `CANCEL_REQUESTED`. Order accepts each of them
while it awaits Shipping (`CreateShipment` and `CancelShipment` share the key
`orderId`, so Shipping always sees them in order):

| Event | What Order does while awaiting Shipping |
|---|---|
| `ShipmentCreated` | stores `shipmentId`, keeps waiting |
| `ShipmentInDelivery` | records the pickup, `FulfillReservation`, keeps waiting |
| `ShipmentDelivered` | `FULFILLED` - the request came too late, the cancellation reason is dropped |
| `ShipmentLost` | reason becomes "Shipment lost", refund requested, no return awaited |
| `ShipmentCancelled` | cancellation confirmed, refund requested; the reason stays "Cancelled by customer" even if the courier cancelled on their own first |

Shipping never answers a late command with an error or a "rejected" event: a
cancellation that lost the race is not a failure, and the order leaves the
waiting phase through one of the events above.

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

**Shipment cancelled BEFORE pickup.** Only the customer can cause it, by
cancelling the order. The reservation hasn't been fulfilled yet - the
compensation mirrors the previous case: `CancelReservation` +
`RefundRequested`.

**Shipment cancelled AFTER pickup.** By the customer cancelling the order
(`cancellationReason` "Cancelled by customer"), or by the courier who couldn't
hand the goods over ("Cancelled by customer" if the recipient refused them,
"Delivery failed" if nobody could take them). Stock has already been physically
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
| `shipping.commands` | Order | Shipping | `CreateShipment`, `CancelShipment` |
| `payment.commands` | Order | Payment | `RefundRequested` |
| `payment.events` | Payment | Order | `PaymentCompleted`, `PaymentFailed`, `RefundCompleted`, `RefundFailed` |
| `shipping.events` | Shipping | Order | `ShipmentCreated`, `ShipmentInDelivery`, `ShipmentDelivered`, `ShipmentCancelled`, `ShipmentLost`, `ShipmentReturnedToInventory` |

Commands (`*.commands`) are an order to a specific service to do one targeted
thing. Events (`*.events`) are a fact that happened. Every message carries
an `eventType`, `correlationId`, `eventId` envelope on top of the JSON
payload, so a consumer on a topic with multiple message types can correctly
recognize what just arrived.
