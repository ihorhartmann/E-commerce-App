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
    [*] --> CREATED: POST /orders/checkout

    CREATED --> RESERVATION_FAILED: Inventory: недостатньо товару
    CREATED --> STOCK_RESERVED: Резервація ОК

    STOCK_RESERVED --> PAYMENT_FAILED: Payment service недоступний/впав\n(до PaymentIntent)
    STOCK_RESERVED --> PAYMENT_SUBMITTED: PaymentIntent створено

    PAYMENT_SUBMITTED --> PAYMENT_FAILED: Stripe webhook: payment_failed
    PAYMENT_SUBMITTED --> PAYMENT_CONFIRMED: Stripe webhook: succeeded

    PAYMENT_CONFIRMED --> SHIPMENT_FAILED: Shipping: не вдалось створити відправлення
    PAYMENT_CONFIRMED --> SHIPMENT_CREATED: Shipment створено (status=Created)

    SHIPMENT_CREATED --> IN_DELIVERY: Shipment status=InDelivery\n(тригерить FulfillReservation)

    IN_DELIVERY --> LOST: Shipment status=Failed
    IN_DELIVERY --> COMPLETED: Shipment status=Delivered

    RESERVATION_FAILED --> [*]
    PAYMENT_FAILED --> [*]
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
    O->>I: REST: POST /api/v1/reservations

    alt Недостатньо товару
        I-->>O: REST: 409 insufficient stock
        O-->>U: REST: 409 error
        Note over O: Order -> RESERVATION_FAILED
    else Резервація ОК
        I-->>O: REST: 200 reserved (reservationId на кожен item)
        Note over O: Order -> STOCK_RESERVED
        O->>P: REST: POST /api/v1/payments

        alt Payment service недоступний / впав
            O--)I: Kafka: CancelReservation
            Note over O: Order -> PAYMENT_FAILED
        else Payment service відповів
            P->>S: REST: create PaymentIntent
            S-->>P: REST: client_secret
            P-->>O: REST: client_secret
            O-->>U: REST: 200 {orderId, client_secret}
            Note over O: Order -> PAYMENT_SUBMITTED

            U->>S: REST: confirmCardPayment (напряму, минаючи бекенд)
            S->>P: REST: webhook POST /webhooks/stripe

            alt Оплата відхилена
                P--)O: Kafka: PaymentFailed
                O--)I: Kafka: CancelReservation
                Note over O: Order -> PAYMENT_FAILED
            else Оплата успішна
                P--)O: Kafka: PaymentCompleted
                Note over O: Order -> PAYMENT_CONFIRMED
                O--)Sh: Kafka: CreateShipment

                alt Не вдалось створити Shipment
                    Sh--)O: Kafka: ShipmentCreationFailed
                    O--)I: Kafka: CancelReservation
                    O--)P: Kafka: RefundRequested
                    Note over O: Order -> CANCELLED
                else Shipment створено
                    Sh--)O: Kafka: ShipmentStatusChanged(Created)
                    Note over O: Order -> SHIPMENT_CREATED

                    Sh--)O: Kafka: ShipmentStatusChanged(InDelivery)
                    O--)I: Kafka: FulfillReservation
                    Note over I: quantity -= qty,\nрезервацію видалено
                    Note over O: Order -> IN_DELIVERY

                    alt Посилку втрачено в дорозі
                        Sh--)O: Kafka: ShipmentStatusChanged(Failed)
                        O--)P: Kafka: RefundRequested
                        Note over O: Order -> LOST -> REFUNDED
                    else Доставлено успішно
                        Sh--)O: Kafka: ShipmentStatusChanged(Delivered)
                        Note over O: Order -> COMPLETED
                    end
                end
            end
        end
    end
```

Статус `Cancelled` у Shipping — валідний стан життєвого циклу посилки
(доступний напряму через REST), але сама сага сьогодні в нього не переходить
жодним із задокументованих кроків вище.

## Компенсаційні кроки — короткий підсумок

**Немає товару на складі.** Найдешевша точка збою — до створення PaymentIntent
і до будь-якого списання коштів. Просто повертаємо помилку фронтенду,
компенсувати нічого не треба.

**Payment service недоступний або впав.** Резервація вже існує (`STOCK_RESERVED`), запит
`POST /api/v1/payments` кинув виняток ще до відповіді від Stripe. Компенсація — та сама дія:
`CancelReservation` в Inventory, звільняємо склад, `Order -> PAYMENT_FAILED`. На
відміну від решти компенсацій це відбувається синхронно, всередині `OrderServiceImpl`, а не
через сагу.

**Оплата відхилена (Stripe).** Резервація вже існує, гроші не списані. Компенсація —
одна дія: `CancelReservation` в Inventory, звільняємо склад, `Order -> PAYMENT_FAILED`.

**Не вдалось створити Shipment.** Гроші вже списані, резервація тримається.
Компенсація подвійна: скасувати резервацію в Inventory **і** повернути кошти
через Payment Service — обидва кроки йдуть через Kafka
(`CancelReservation`, `RefundRequested`), REST тут більше не
використовується.

**Посилку втрачено вже після pickup.** Склад фізично зменшений
(`FulfillReservation` уже виконано) — повертати товар на склад нема сенсу,
компенсація тут не "відкат" попереднього кроку, а новий крок: `RefundRequested`
через Kafka в Payment. Це точка неповернення саги — до цього моменту всі збої
компенсуються поверненням до попереднього стану, після — лише грошовою
компенсацією.

## REST vs Kafka — де і чому

Резервація товару (`POST /api/v1/reservations`) та створення Stripe
PaymentIntent (`POST /api/v1/payments`) — синхронні REST-виклики з Order, бо
фронтенд чекає на відповідь у межах одного HTTP-запиту
(`POST /orders/checkout`) і має отримати або помилку "немає товару", або
`client_secret` для форми оплати. Все, що відбувається після появи сторінки
оплати — асинхронне через Kafka, бо ніхто синхронно не чекає на ці кроки:
підтвердження оплати, створення відправлення, зміна статусу доставки,
компенсації (включно з refund — раніше refund йшов через REST `POST
/refunds`, але жодна компенсація не є time-sensitive для фронтенду, тож
`RefundRequested` тепер теж Kafka-подія, а не REST-виклик).

## Kafka-топіки

Топік на кожен домен-виробник + тип повідомлення (command/event), а не на
кожен окремий тип події — див. обговорення переваг цього підходу окремо.
Порядок повідомлень одного агрегату (замовлення/посилки) гарантується
ключем партиціювання (`orderId`/`shipmentId`), а не розбиттям на топіки.

| Топік | Продюсер | Консьюмер | Повідомлення |
|---|---|---|---|
| `inventory.commands` | Order | Inventory | `CancelReservation`, `FulfillReservation` |
| `shipping.commands` | Order | Shipping | `CreateShipment` |
| `payment.commands` | Order | Payment | `RefundRequested` |
| `payment.events` | Payment | Order | `PaymentCompleted`, `PaymentFailed` |
| `shipping.events` | Shipping | Order | `ShipmentCreationFailed`, `ShipmentStatusChanged` |

Команди (`*.commands`) — наказ конкретному сервісу щось зробити, одна цільова
дія. Події (`*.events`) — факт, що стався; сьогодні їх читає лише Order, але
формат не прив'язаний до єдиного консьюмера. Кожне повідомлення несе
конверт `eventType` + `correlationId` (+ `eventId`) поверх JSON-пейлоада, щоб
консьюмер топіка з кількома типами повідомлень міг коректно розпізнати, що
саме прийшло.
