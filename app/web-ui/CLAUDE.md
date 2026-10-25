# Web UI Architecture

**Architecture:** MVVM, packages by feature

**Language:** Kotlin/JS (compiled to JavaScript, bundled by webpack)

**Technologies:** Compose HTML, Ktor Client, kotlinx.serialization, kotlinx.coroutines, Stripe.js; nginx in the container

## What it is

A browser client of the Gateway, for every role: the catalog (everyone; admins create and edit products), the cart
and checkout with the Stripe payment (customers), the customer's orders, and the courier's shipments. It holds no
business rules of its own beyond checking a form before sending it - the services own all of them.

## Deployment and authentication

* The production build is static files (`build/dist/js/productionExecutable`), served by nginx
  (`nginx/default.conf.template`). nginx also forwards `/api`, `/oauth2`, `/login` and `/logout` to the Gateway, so
  the browser sees a single origin: the Gateway's session cookie, its CSRF cookie and the OAuth2 redirects all work
  without CORS. The Gateway knows nothing about the UI.
* Signing in is a full page navigation to `/oauth2/authorization/keycloak`; signing out is a form POST to `/logout`
  carrying the CSRF token (`BrowserAuthFlowLauncher`). The browser never sees a token - only the `HttpOnly` session cookie.
* `GET /api/v1/me` (the Gateway's own endpoint) tells who is signed in and their roles; `204 No Content` means a
  guest (not `401`, which the browser would log as a failed request on every page load). The UI
  uses the roles only to choose what to show - every service still checks them itself.
* Every state-changing request carries the CSRF token (`X-XSRF-TOKEN`, from the `XSRF-TOKEN` cookie) and an
  `Idempotency-Key` (`HttpClientFactory`). A request that got no response at all is retried with the same key.
* Values that differ per environment come from `/config.json`, which nginx renders from env vars (in development,
  the dev server - `webpack.config.d/dev-server.js`). The container doesn't start if `WEB_UI_PORT`,
  `GATEWAY_ADDRESS` or `STRIPE_PUBLISHABLE_KEY` is missing (`nginx/00-require-env.sh`).
* The Stripe card form is Stripe's own iframe, mounted into a plain `<div>` the checkout screen renders; Stripe.js is
  loaded from Stripe's domain by `index.html`, never bundled.

## Packages

```text
com.hartmann/
├── Main.kt                                  start-up: loads /config.json, then renders the app
├── app/                                     App (the shell: top bar, screen per role) with its SessionViewModel,
│                                            AppDependencies (wiring)
├── core/                                    shared by every feature, depends on none of them
│   ├── network/                             the Ktor client, error statuses (exception/), shared DTOs (dto/),
│   │                                        /config.json
│   ├── model/                               Money (minor units - JS has no decimal type), Page
│   ├── ui/                                  reusable composables (buttons, fields, dialog, pagination, toasts)
│   ├── error/                               ErrorHandler, launchSafely (a ViewModel's coroutines)
│   ├── notification/                        Notifier (toasts)
│   └── auth/                                the session: who is signed in (SessionApi, CurrentUser, Role),
│                                            AuthFlowLauncher (starts sign in/out)
└── feature/
    ├── catalog/     products (inventory)     CatalogViewModel, CatalogScreen
    ├── cart/        the cart (sessionStorage) CartStore
    ├── checkout/    cart, address, payment    CheckoutViewModel, CheckoutScreen, payment/ (PaymentGateway, Stripe)
    ├── order/       the customer's orders     OrdersViewModel, OrdersScreen; OrderApi, also used by checkout
    └── shipment/    the courier's shipments   ShipmentsViewModel, ShipmentsScreen
```

Features may use each other (checkout orders through `order/` and empties `cart/`; catalog fills `cart/`); `core/`
never uses a feature.

Within a feature package:

* `XxxScreen.kt` - composables only: renders the ViewModel's state and passes user actions to it.
* `XxxViewModel.kt` - the screen's state (`StateFlow`) and actions. It depends on interfaces only (`XxxApi`,
  `CartStore`, `PaymentGateway`, `Notifier`, `ErrorHandler`), and runs its work on the screen's own coroutine scope,
  so leaving the screen cancels whatever still runs (e.g. polling an order). It's a plain class: its actions run
  through `scope.launchSafely(errorHandler) { ... }`, which hands any failure to the `ErrorHandler`.
* `XxxApi.kt` - the port: suspend functions taking and returning `model/` types. `KtorXxxApi.kt` implements it; its
  wire format lives in `dto/`, with `fromXxx`/`toXxx` mappers next to each DTO.
* `model/` - the UI's own types, with the logic that belongs to them (e.g. `OrderStatus.isCancellable`,
  `ProductForm.validationError`).

There are no use cases or repositories between a ViewModel and its API: the UI has no business logic for them to
hold.

## Running

* Production bundle: `./gradlew jsBrowserDistribution`.
* Dev server with live reload, with the rest of the stack running in Docker:
  `WEB_UI_PORT=3000 GATEWAY_URL=http://localhost:8080 STRIPE_PUBLISHABLE_KEY=pk_test_... ./gradlew jsBrowserDevelopmentRun --continuous`.
* Tests: `./gradlew jsNodeTest`.

## Tests

The ViewModels are unit-tested (`src/jsTest`), and so is `ensureSuccess` - the one place that reads the services'
error responses (RFC 9457 problem details), with Ktor's `MockEngine` answering real response bodies. The tests follow
the root CLAUDE.md test rules, with these differences forced by the platform:

* Mocks are made with [Mokkery](https://mokkery.dev) (`mock<T>()`, `everySuspend`, `verifySuspend(exactly(n))`,
  `verify(not)`): MockK and Mockito rely on the JVM and don't run on Kotlin/JS. Mokkery generates mocks at compile
  time, so it mocks interfaces - another reason ViewModels depend on interfaces only.
* `@TestInstance(TestInstance.Lifecycle.PER_METHOD)` doesn't apply: it's a JUnit 5 annotation, and Kotlin/JS tests
  run on `kotlin.test` (Mocha on Node), which always creates a new instance of the test class per test.
* The tests run on Node.js, which has no DOM: nothing a ViewModel touches may need the browser (e.g. the payment form
  is mounted by a CSS selector, not by an `HTMLElement`).
* A ViewModel under test gets `backgroundScope` of `runTest`; `runCurrent()`/`advanceTimeBy()` drive its coroutines,
  so a polling loop doesn't keep the test from finishing.
