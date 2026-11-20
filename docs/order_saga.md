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

`Order` keeps **facts**, not a status: each step of the saga leaves its own
nullable timestamp - `paymentConfirmedAt`, `cancellationRequestedAt` (with its
`cancellationReason`), `cancelledAt`, `shipment.pickedUpAt`,
`shipment.deliveredAt`, `shipment.returnedAt`, `refund.requestedAt`,
`refund.confirmedAt`, `refund.failedAt` - plus an `awaitingReturn` flag. Every
rule asks the facts (is it paid, is its shipment still open), never the status:
the saga's events may come late, twice or out of order, and a fact answers what
a rule actually needs to know.

`OrderStatus` is what the facts add up to, computed in one place
(`Order.status`), the first that applies:

| Fact | Status |
|---|---|
| `shipment.deliveredAt` | `FULFILLED` - a delivery wins over a cancellation that came too late |
| `cancelledAt` | `CANCELLED` |
| `cancellationRequestedAt` | `CANCEL_REQUESTED` |
| `paymentConfirmedAt` | `PROCESSING` |
| none of them | `PLACED` |

The repository stores it next to the facts, for queries (a customer's orders,
the orders-by-status metrics), and checks on load that it still matches them.

The status follows the customer's side of the order. Logistics the customer
has no part in - the courier bringing cancelled goods back to the warehouse -
is recorded on the order (`awaitingReturn`, `shipment.returnedAt`) but never
holds its status back, and isn't part of the customer's `OrderResponse`.

```mermaid
stateDiagram-v2
    [*] --> PLACED: place order
    PLACED --> PROCESSING: 1 paid
    PLACED --> CANCELLED: 2 payment service failed<br/>3 payment expired<br/>4 cancelled unpaid,<br/>PaymentCancelled first
    PLACED --> CANCEL_REQUESTED: 5 cancelled unpaid
    PROCESSING --> FULFILLED: 6 delivered
    PROCESSING --> CANCEL_REQUESTED: 7 customer cancels<br/>8 shipment lost<br/>9 courier couldn't<br/>hand over
    CANCEL_REQUESTED --> CANCEL_REQUESTED: 10 shipment cancelled<br/>or lost - refund requested<br/>11 refund failed
    CANCEL_REQUESTED --> CANCELLED: 12 payment cancelled<br/>or expired<br/>13 refund confirmed
    CANCEL_REQUESTED --> FULFILLED: 14 delivered,<br/>request too late
    CANCELLED --> [*]
    FULFILLED --> [*]
```

| # | Transition | When | `cancellationReason` |
|---|---|---|---|
| 1 | `PLACED → PROCESSING` | the payment is confirmed (`PaymentCompleted`) | |
| 2 | `PLACED → CANCELLED` | the payment-service failed at checkout | `PAYMENT_FAILED` |
| 3 | `PLACED → CANCELLED` | the payment expired, unpaid for 15 minutes (`PaymentExpired`) | `PAYMENT_EXPIRED` |
| 4 | `PLACED → CANCELLED` | the customer cancelled the unpaid order, and `PaymentCancelled` arrived before Order stored the request | `CANCELLED_BY_CUSTOMER` |
| 5 | `PLACED → CANCEL_REQUESTED` | the customer cancelled the unpaid order: Payment cancelled the payment at Stripe and answered, and Order stored the request - the usual case; `PaymentCancelled` then finishes it (12) | `CANCELLED_BY_CUSTOMER` |
| 6 | `PROCESSING → FULFILLED` | delivered (`ShipmentDelivered`) | |
| 7 | `PROCESSING → CANCEL_REQUESTED` | the customer asked to cancel the paid order | `CANCELLED_BY_CUSTOMER` |
| 8 | `PROCESSING → CANCEL_REQUESTED` | the shipment is lost (`ShipmentLost`), refund requested | `SHIPMENT_LOST` |
| 9 | `PROCESSING → CANCEL_REQUESTED` | the courier couldn't hand the goods over (`ShipmentCancelled`), refund requested | `CANCELLED_BY_CUSTOMER` (refused) / `DELIVERY_FAILED` (nobody there) |
| 10 | `CANCEL_REQUESTED → CANCEL_REQUESTED` | Shipping confirmed the cancellation, or the shipment was lost first - refund requested | `CANCELLED_BY_CUSTOMER` / `SHIPMENT_LOST` |
| 11 | `CANCEL_REQUESTED → CANCEL_REQUESTED` | the refund failed (`RefundFailed`) - needs manual intervention | |
| 12 | `CANCEL_REQUESTED → CANCELLED` | unpaid: the payment cancelled or expired (`PaymentCancelled`/`PaymentExpired`) | the customer's, kept |
| 13 | `CANCEL_REQUESTED → CANCELLED` | the refund is confirmed (`RefundCompleted`) | |
| 14 | `CANCEL_REQUESTED → FULFILLED` | delivered before the request reached Shipping: the customer got the goods, the request came too late | none - cleared, with the request |

`CANCEL_REQUESTED` means only that the order is being cancelled, whatever
stage it's at. Its facts tell the phases apart:

1. **unpaid** (no `paymentConfirmedAt`) - Payment has already cancelled the
   payment, so it can no longer be paid; `PaymentCancelled` finishes the
   cancellation (see "Customer cancels an unpaid order" below);
2. **awaiting shipping** (paid, no `refund`) - the customer asked to cancel, and Shipping hasn't
   answered yet. Nothing is refunded or released, and the shipment's events
   are still accepted (see "Customer cancels a paid order" below);
3. **cancellation confirmed** (`refund` requested) - the shipment is cancelled or lost, the refund
   is requested, and the order waits for its confirmation. If the courier had
   already picked the goods up, they also have to bring them back to the
   warehouse - but the order doesn't wait for that: once the refund is
   confirmed it is `CANCELLED`, even if the goods are still on their way back.

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

        alt Storing the order fails
            O--)I: Kafka: CancelReservation
            O-->>U: HTTP: error
            Note over O: no order is created
        else Order stored
            Note over O: Order.createNew(id, reservedItems) -> PLACED, stored
            O->>P: HTTP: POST /api/v1/payments

            alt Payment service unavailable / crashed
                Note over O: Order.failPayment() -> CANCELLED, reason=PAYMENT_FAILED
                O--)I: Kafka: CancelReservation
                O-->>U: HTTP: error
            else Payment service responded
                P->>S: HTTP: create PaymentIntent
                S-->>P: HTTP: client_secret
                P-->>O: HTTP: client_secret
                O-->>U: HTTP: 201 {order, client_secret}

                U->>S: HTTP: confirmPayment (Stripe.js, directly, bypassing the backend)
                S->>P: HTTP: webhook POST /api/v1/webhooks/stripe

                alt Payment declined
                    Note over P: Payment -> ATTEMPT_DECLINED, still open:<br/>no event, the customer may pay again
                    Note over O: stays PLACED - until paid, cancelled<br/>by the customer, or expired (15 min)
                else Payment succeeded
                    P--)O: Kafka: PaymentCompleted
                    Note over O: Order.confirmPayment() -> PROCESSING, paymentConfirmedAt
                    O--)Sh: Kafka: CreateShipment
                    Note over Sh: on failure retried, then dead-lettered<br/>(shipping.commands.DLT) - no event back to Order

                    Sh--)O: Kafka: ShipmentCreated
                    Note over O: Order.createShipment(shipmentId)<br/>(stays PROCESSING)
                    O--)I: Kafka: HoldReservation
                    Note over I: reservation holder -> Courier
                end
            end
        end
    end
```

A declined card doesn't end the payment: Stripe's `payment_intent.payment_failed` only marks it
`ATTEMPT_DECLINED`, and the same PaymentIntent can still be paid with another card. Nothing is sent to Order, which
keeps the order `PLACED` - the customer pays again, cancels it (see "Customer cancels an unpaid order"), or the
expiration sweep closes it after 15 minutes (see "Unpaid payment expires").

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
        Note over O: Order.loseShipment() -- refund requested<br/>-> CANCEL_REQUESTED, reason=SHIPMENT_LOST
        O--)P: Kafka: RefundRequested

    else Goods couldn't be handed over
        C->>Sh: HTTP: POST /shipments/{id}/cancel {reason}
        Note over Sh: In Delivery -> Cancelled, reason as given,<br/>goods stay with the courier
        Sh--)O: Kafka: ShipmentCancelled {reason}
        Note over O: Order.cancelShipment(reason)<br/>-> CANCEL_REQUESTED, awaitingReturn=true, refund requested<br/>RecipientRefused -> reason=CANCELLED_BY_CUSTOMER<br/>RecipientUnavailable -> reason=DELIVERY_FAILED
        O--)P: Kafka: RefundRequested

        C->>Sh: HTTP: POST /shipments/{id}/return
        Sh--)O: Kafka: ShipmentReturnedToInventory
        Note over O: Order.returnToStock() -- shipment.returnedAt<br/>-- awaitingReturn=false,<br/>status unchanged (CANCELLED once refunded)
        O--)I: Kafka: Restock
        Note over I: onHand += qty (per order item)
    end

    alt Refund confirmed by Stripe
        P--)O: Kafka: RefundCompleted
        Note over O: Order.confirmRefund(amount) -- refund.confirmedAt/amount<br/>-> CANCELLED, even while the goods<br/>are still on their way back
    else Refund failed
        P--)O: Kafka: RefundFailed
        Note over O: Order.failRefund() -- refund.failedAt,<br/>stays CANCEL_REQUESTED (needs manual intervention)
    end
```

`RefundCompleted`/`RefundFailed` and `ShipmentReturnedToInventory` are two
independent events that can arrive in either order (refund confirmation is
typically much faster than the courier's physical trip back). Only the refund
finalizes the order: `Order.confirmRefund` makes it `CANCELLED` whether or not
the goods are back. `Order.returnToStock` only records the return - it accepts
it once, and only while `awaitingReturn` is set, so a return the order doesn't
wait for (or a repeated one) is rejected instead of restocking twice.

## Protocol: customer cancels a paid order

The customer cancels the **order**, by calling Order - never shipping- or
payment-service directly. For an unpaid order, Order first cancels its payment
(see "Customer cancels an unpaid order" below); a paid one, whose shipment
hasn't ended (`PROCESSING`), as described here; a `FULFILLED` one is rejected
with `409`.
Asking again while the order is being cancelled, or once it's cancelled,
changes nothing: the order comes back as it is.

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
    Note over O: Order.requestCancellation()<br/>-> CANCEL_REQUESTED, reason=CANCELLED_BY_CUSTOMER<br/>no refund yet
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
| `ShipmentLost` | reason becomes `SHIPMENT_LOST`, refund requested, no return awaited |
| `ShipmentCancelled` | cancellation confirmed, refund requested; the reason stays `CANCELLED_BY_CUSTOMER` even if the courier cancelled on their own first |

Shipping never answers a late command with an error or a "rejected" event: a
cancellation that lost the race is not a failure, and the order leaves the
waiting phase through one of the events above.

## Protocol: customer cancels an unpaid order

An unpaid order's PaymentIntent is still payable, so it must be cancelled at
Stripe before anything else - otherwise the customer could still pay an order
that is already cancelled, its stock already released. Order asks Payment
synchronously, outside any database transaction, and only once Payment has
answered does it mark the order being
cancelled (`CANCEL_REQUESTED`) - in a transaction of its own, on the order
reloaded, since it may have changed while Payment was called. Payment's
`PaymentCancelled`, written to its outbox in the same transaction as the
cancelled payment, then finishes the cancellation.

Order's own commit of `CANCEL_REQUESTED` and its handling of
`PaymentCancelled` can come in either order, and neither depends on the other:

* `PaymentCancelled` is handled first: the order reloaded after Payment's
  answer is already `CANCELLED` - cancelled by the event, not by the request -
  and the request returns it as it is;
* Order fails to store `CANCEL_REQUESTED` after Payment's answer (or crashes):
  the order stays `PLACED` until `PaymentCancelled` cancels it all the same - it
  accepts an unpaid order whether or not it was marked. The customer, told of a
  failure, may ask again: Payment answers a payment it already cancelled as it
  is, so the retry succeeds.

`PaymentExpired` accepts an unpaid order being cancelled too - Payment answers
`200` for a payment closed in any way, and the event that closed it may be the
expiration - and keeps the customer's reason: the order ends the way they asked
for.

```mermaid
sequenceDiagram
    autonumber
    actor U as Frontend
    participant O as Order Service
    participant P as Payment Service
    participant S as Stripe
    participant I as Inventory Service

    U->>O: HTTP: POST /api/v1/orders/{id}/cancel
    Note over O: Order.isPaymentOpen() - not changed yet
    O->>P: HTTP: POST /api/v1/payments/{orderId}/cancel
    P->>S: HTTP: cancel PaymentIntent (key cancelOrder-{orderId})

    alt Cancelled (or already cancelled by an earlier call)
        S-->>P: canceled
        Note over P: Payment.cancel() -> CANCELLED,<br/>PaymentCancelled to the outbox - one transaction
        par HTTP answer
            P-->>O: HTTP: 200 payment
            Note over O: reloaded, Order.requestCancellation()<br/>-> CANCEL_REQUESTED, reason=CANCELLED_BY_CUSTOMER<br/>(already CANCELLED: returned as it is)
            O-->>U: HTTP: 200 order
        and Kafka event, sent by the outbox relay - either order
            P--)O: Kafka: PaymentCancelled
            Note over O: Order.cancelPayment() -> CANCELLED,<br/>nothing charged, nothing to refund
            O--)I: Kafka: CancelReservation
        end
    else Already paid - the webhook hasn't told yet
        S-->>P: succeeded
        Note over P: confirmed, as the webhook would,<br/>PaymentCompleted to the outbox - one transaction
        par HTTP answer
            P-->>O: HTTP: 409 PAYMENT.PAYMENT_ALREADY_CONFIRMED
            O-->>U: HTTP: 409 ORDER.PAYMENT_ALREADY_CONFIRMED
        and Kafka event, sent by the outbox relay - either order
            P--)O: Kafka: PaymentCompleted
            Note over O: moves the order on as a paid one,<br/>which the customer may still cancel - with a refund
        end
    else Payment still processing
        S-->>P: processing
        P-->>O: HTTP: 503, retryable
        O-->>U: HTTP: 503 - try again shortly
    end
```

A payment that is already cancelled, expired or failed is answered as it is,
with no new event: whatever closed it already told Order. If Stripe cancelled
the PaymentIntent but Payment failed to store it, the customer's next attempt
finds the PaymentIntent cancelled at Stripe and finishes the job; without one,
the expiration sweep does (see below).

## Protocol: unpaid payment expires

Payment's expiration sweep gives up on every open payment (`INITIATED`,
`PENDING`, `ATTEMPT_DECLINED`) past its `expiresAt` (15 minutes), one by one,
calling Stripe outside any database transaction:

1. its PaymentIntent, if it got one, is cancelled at Stripe first (key
   `expirePayment-{orderId}`, reason `abandoned`), so an expired payment can no
   longer be paid;
2. Stripe cancelled it: the payment is expired and `PaymentExpired` cancels the
   order (reason `PAYMENT_EXPIRED`) and releases its stock;
3. the customer paid it at the last moment: the payment is confirmed instead,
   and `PaymentCompleted` moves the order on - the webhook arriving later
   changes nothing;
4. Stripe is still processing it: it is left for the next run.

A payment that fails - Stripe unavailable, a concurrent update - is logged and
left open for the next run, which finds a PaymentIntent it already cancelled
at Stripe cancelled and finishes the job.

## Compensation steps - quick summary

**Out of stock.** The cheapest failure point - before the order is stored, before
the PaymentIntent is created and before any charge. Just return an error to the
frontend: no order exists, so there is nothing to compensate.

**Storing the order failed.** The stock is reserved, but there is no order to
hold it for. The compensation is one action: `CancelReservation` in Inventory.

**Payment service unavailable or crashed.** The reservation already exists,
the `POST /api/v1/payments` request threw before Stripe ever responded. The
order is cancelled (`PAYMENT_FAILED`), and the compensation is the same action:
`CancelReservation` in Inventory.

**Payment declined (Stripe).** Not a failure of the saga: the payment stays
open (`ATTEMPT_DECLINED`) and can be paid with another card, so nothing is
compensated. If it never is, it ends as an expired payment (below).

**Payment expired, or cancelled by the customer before paying.** No money was
charged, and the PaymentIntent is cancelled at Stripe so it can no longer be.
The compensation is one action: `CancelReservation` in Inventory.

**Shipment creation failed.** Not compensated automatically: Shipping retries
`CreateShipment`, then dead-letters it (`shipping.commands.DLT`) and tells Order
nothing, so the order stays `PROCESSING` - paid, its stock reserved. It needs
the cause fixed and the command redriven by hand.

**Shipment cancelled BEFORE pickup.** Only the customer can cause it, by
cancelling the order. The reservation hasn't been fulfilled yet - two actions:
`CancelReservation` in Inventory and `RefundRequested` to Payment.

**Shipment cancelled AFTER pickup.** By the customer cancelling the order
(`cancellationReason` `CANCELLED_BY_CUSTOMER`), or by the courier who couldn't
hand the goods over (`CANCELLED_BY_CUSTOMER` if the recipient refused them,
`DELIVERY_FAILED` if nobody could take them). Stock has already been physically
decremented (`FulfillReservation` already ran) - `CancelReservation` makes no
sense here. Instead, two independent things follow: the refund, whose
confirmation makes the order `CANCELLED`, and the courier physically returning
the goods (`Restock` in Inventory), which the order records but which doesn't
change its status.

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
| `payment.events` | Payment | Order | `PaymentCompleted`, `PaymentExpired`, `PaymentCancelled`, `RefundCompleted`, `RefundFailed` |
| `shipping.events` | Shipping | Order | `ShipmentCreated`, `ShipmentInDelivery`, `ShipmentDelivered`, `ShipmentCancelled`, `ShipmentLost`, `ShipmentReturnedToInventory` |

Commands (`*.commands`) are an order to a specific service to do one targeted
thing. Events (`*.events`) are a fact that happened. Every message carries the
Kafka headers `messageType`, `messageId` and `correlationId` (the order id)
next to its JSON payload, so a consumer on a topic with multiple message types
can tell what just arrived, and a repeated message is recognized by its
`messageId`.

Every consumed topic has a dead-letter topic, `<topic>.DLT`, declared by its
consumer: a message that fails permanently goes there at once, one that fails
transiently after 5 attempts (1, 2, 4, 8 s apart). Nothing is redriven
automatically - see "Kafka topics, retries and dead-lettering" in the root
`CLAUDE.md`.
