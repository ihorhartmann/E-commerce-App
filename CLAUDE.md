# TODO start, run, shutdown
всі сервіси отримують необхідні змінні для підключення до кафки та бази даних через env vars.
Якщо змінної немає - сервіс не стартує і виводить помилку в лог.

Всі сервіси мають мати graceful shutdown, щоб при отриманні сигналу завершення роботи сервіс міг закрити всі підключення до кафки та бази даних.

Всі сервіси мають мати healthcheck endpoint, який повертає статус сервісу

Шари Domain/Entity для кожного сервісу мають відповідати принципу Information Expert (перефразувати це для ШІ в контексті валідації).


# Винятки, помилки та DTO

* В Java/Kotlin сервісах винятки (exceptions) мають лежати в окремому пекеджі `exception`,
  розташованому біля файлу(ів), де вони використовуються.
* DTO (Java/Kotlin/Go) мають лежати в окремому пекеджі `dto`, розташованому біля файлу(ів),
  де вони використовуються.
* В Go сервісах помилки (errors) мають лежати у файлі `errors.go`, розташованому біля
  файлу(ів), де вони використовуються.
* Exceptions, errors та DTO потрібно оголошувати на максимально зовнішньому шарі архітектури,
  який можливо:
  * якщо DTO/exception потрібен у сигнатурі порту (інтерфейсу) внутрішнього шару - оголошувати
    біля цього порту;
  * якщо він потрібен лише конкретній імплементації цього порту в зовнішньому шарі (наприклад,
    адаптеру) - оголошувати біля цього адаптера, а не біля порту.


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
