# Order Saga

Order Service — оркестратор саги оформлення замовлення. Документ описує повний
шлях замовлення від чекауту до доставки клієнту: хто з ким як спілкується
(REST чи Kafka) і що відбувається при збої на кожному кроці.

## Легенда

| Позначення | Означає |
|---|---|
| Суцільна стрілка (`->>` / `-->>`) | Синхронний REST-виклик і його відповідь |
| Пунктирна стрілка (`--)`) | Асинхронне повідомлення через Kafka |
| `alt / else` блок | Гілка збою і відповідна компенсація |

## Стани замовлення

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /orders/checkout

    PENDING --> RESERVATION_FAILED: Inventory: недостатньо товару
    PENDING --> AWAITING_PAYMENT: Резервація ОК + PaymentIntent створено

    AWAITING_PAYMENT --> PAYMENT_FAILED: Stripe webhook: payment_failed
    AWAITING_PAYMENT --> PAYMENT_CONFIRMED: Stripe webhook: succeeded

    PAYMENT_CONFIRMED --> SHIPMENT_FAILED: Shipping: не вдалось створити відправлення
    PAYMENT_CONFIRMED --> SHIPMENT_CREATED: Shipment створено (status=Created)

    SHIPMENT_CREATED --> IN_DELIVERY: Shipment status=InTransit\n(тригерить FulfillReservation)

    IN_DELIVERY --> LOST: Shipment status=Failed
    IN_DELIVERY --> COMPLETED: Shipment status=Delivered

    RESERVATION_FAILED --> [*]
    PAYMENT_FAILED --> CANCELLED
    SHIPMENT_FAILED --> CANCELLED
    LOST --> REFUNDED

    CANCELLED --> [*]
    REFUNDED --> [*]
    COMPLETED --> [*]
```

## Повний протокол (REST + Kafka) з компенсаціями

```mermaid
sequenceDiagram
    autonumber
    actor U as Frontend
    participant O as Order Service
    participant I as Inventory Service
    participant P as Payment Service
    participant S as Stripe
    participant Sh as Shipping Service

    U->>O: REST: POST /orders/checkout
    O->>I: REST: POST /reservations

    alt Недостатньо товару
        I-->>O: REST: 409 insufficient stock
        O-->>U: REST: 409 error
        Note over O: Order -> RESERVATION_FAILED
    else Резервація ОК
        I-->>O: REST: 200 reserved
        O->>P: REST: POST /payment-intents
        P->>S: REST: create PaymentIntent
        S-->>P: REST: client_secret
        P-->>O: REST: client_secret
        O-->>U: REST: 200 {orderId, client_secret}
        Note over O: Order -> AWAITING_PAYMENT

        U->>S: REST: confirmCardPayment (напряму, минаючи бекенд)
        S->>P: REST: webhook POST /webhooks/stripe

        alt Оплата відхилена
            P--)O: Kafka: PaymentFailed
            O--)I: Kafka: CancelReservationMessage
            Note over O: Order -> CANCELLED
        else Оплата успішна
            P--)O: Kafka: PaymentCompleted
            Note over O: Order -> PAYMENT_CONFIRMED
            O--)Sh: Kafka: CreateShipment

            alt Не вдалось створити Shipment
                Sh--)O: Kafka: ShipmentCreationFailed
                O--)I: Kafka: CancelReservationMessage
                O->>P: REST: POST /refunds
                Note over O: Order -> CANCELLED
            else Shipment створено
                Sh--)O: Kafka: ShipmentStatusChanged(Created)
                Note over O: Order -> SHIPMENT_CREATED

                Sh--)O: Kafka: ShipmentStatusChanged(InTransit)
                O--)I: Kafka: FulfillReservation
                Note over I: onHand -= qty,\nрезервацію видалено
                Note over O: Order -> IN_DELIVERY

                alt Посилку втрачено в дорозі
                    Sh--)O: Kafka: ShipmentStatusChanged(Failed)
                    O->>P: REST: POST /refunds
                    Note over O: Order -> LOST -> REFUNDED
                else Доставлено успішно
                    Sh--)O: Kafka: ShipmentStatusChanged(Delivered)
                    Note over O: Order -> COMPLETED
                end
            end
        end
    end
```

## Компенсаційні кроки — короткий підсумок

**Немає товару на складі.** Найдешевша точка збою — до створення PaymentIntent
і до будь-якого списання коштів. Просто повертаємо помилку фронтенду,
компенсувати нічого не треба.

**Оплата відхилена.** Резервація вже існує, гроші не списані. Компенсація —
одна дія: `CancelReservationMessage` в Inventory, звільняємо склад.

**Не вдалось створити Shipment.** Гроші вже списані, резервація тримається.
Компенсація подвійна: скасувати резервацію в Inventory **і** повернути кошти
через Stripe refund у Payment Service.

**Посилку втрачено вже після pickup.** Склад фізично зменшений
(`FulfillReservation` уже виконано) — повертати товар на склад нема сенсу,
компенсація тут не "відкат" попереднього кроку, а новий крок: refund через
Payment. Це точка неповернення саги — до цього моменту всі збої
компенсуються поверненням до попереднього стану, після — лише грошовою
компенсацією.

## REST vs Kafka — де і чому

Резервація товару та створення Stripe PaymentIntent — синхронні REST-виклики
з Order, бо фронтенд чекає на відповідь у межах одного HTTP-запиту
(`POST /orders/checkout`) і має отримати або помилку "немає товару", або
`client_secret` для форми оплати. Все, що відбувається після появи сторінки
оплати — асинхронне через Kafka, бо ніхто синхронно не чекає на ці кроки:
підтвердження оплати, створення відправлення, зміна статусу доставки,
компенсації.
