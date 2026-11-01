package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.entities.model.CancellationReason
import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.model.exception.OrderErrorCode
import com.hartmann.entities.model.exception.OrderNotCancellableException
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.exception.InvalidAddressException
import com.hartmann.entities.value.exception.ValueErrorCode
import com.hartmann.frameworks.config.SecurityConfig
import com.hartmann.interfaceadapter.`in`.http.exception.HttpErrorCode
import com.hartmann.interfaceadapter.`in`.http.idempotency.HttpIdempotencyService
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestAttempt
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestResult
import com.hartmann.interfaceadapter.out.http.inventory.exception.InsufficientStockException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryErrorCode
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryUnavailableException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentErrorCode
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentServiceUnavailableException
import com.hartmann.usecase.port.`in`.usecase.CancelOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.FindCustomerOrdersUseCase
import com.hartmann.usecase.port.`in`.usecase.GetOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import com.hartmann.usecase.port.`in`.usecase.exception.InvalidPageException
import com.hartmann.usecase.port.`in`.usecase.exception.UseCaseErrorCode
import com.hartmann.usecase.port.out.idempotency.dto.HttpRequestRecord
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import com.hartmann.usecase.port.out.repository.exception.ConcurrentUpdateException
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import com.hartmann.usecase.port.out.repository.exception.RepositoryErrorCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@WebMvcTest(OrderController::class, properties = ["oidc.roles-claim=realm_access.roles"])
@Import(SecurityConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var placeOrderUseCase: PlaceOrderUseCase

    @MockitoBean
    private lateinit var cancelOrderUseCase: CancelOrderUseCase

    @MockitoBean
    private lateinit var getOrderUseCase: GetOrderUseCase

    @MockitoBean
    private lateinit var findCustomerOrdersUseCase: FindCustomerOrdersUseCase

    // IdempotencyFilter runs ahead of every controller in this slice too, so it needs a bean here
    // regardless of whether a given test cares about idempotency itself. Stubbing this
    // unconditionally in @BeforeEach is harmless for the GET tests too - Spring's @WebMvcTest
    // doesn't enforce strict/unnecessary-stubbing checks the way MockitoExtension does.
    @MockitoBean
    private lateinit var httpIdempotencyService: HttpIdempotencyService

    // SecurityConfig's real decoder would fetch Keycloak's keys; the tests authenticate with jwt() instead
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @BeforeEach
    fun stubIdempotencyStart() {
        whenever(httpIdempotencyService.claim(any(), any(), any())).thenReturn(RequestAttempt.claimed())
    }

    // the subject is the customer id the controller passes on - a UUID, as Keycloak issues it
    private fun clientJwt(customerId: UUID = UUID.randomUUID()) =
        jwt().jwt { it.subject(customerId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_CLIENT"))

    private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

    private fun testItem() = OrderItem.createExisting(1, 1, 1, "Keyboard", 2, Money(BigDecimal("10.00"), Currency.EUR))

    private fun testOrder(id: Long = 1, customerId: UUID = UUID.randomUUID()) = Order.createExisting(
        id = id,
        customerId = customerId,
        items = listOf(testItem()),
        address = testAddress(),
        status = OrderStatus.PLACED,
        createdAt = Instant.now(),
    )

    private val validPlaceOrderRequestJson: String = """
        {
          "items": [{"productId": 1, "quantity": 2}],
          "address": {"city": "Kyiv", "postalCode": "01001", "line1": "Khreshchatyk 1"}
        }
    """.trimIndent()

    @Test
    fun `placeOrder - returns 201 with the created order`() {
        val clientSecret = "secret_123"
        val order = testOrder()
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenReturn(CheckoutResult(order, clientSecret))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-1")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(order.id) }
            jsonPath("$.clientSecret") { value(clientSecret) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - places the order for the customer from the token, with the requested items and the address from the body`() {
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val requestedItems = listOf(RequestedItem(productId = 1, quantity = 2))
        val address = testAddress()
        whenever(placeOrderUseCase.execute(customerId, requestedItems, address))
            .thenReturn(CheckoutResult(testOrder(customerId = customerId), "secret_123"))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt(customerId))
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-10")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isCreated() }
            jsonPath("$.customerId") { value(customerId.toString()) }
        }

        verify(placeOrderUseCase, times(1)).execute(customerId, requestedItems, address)
    }

    @Test
    fun `placeOrder - returns 400 when the body lacks a required field`() {
        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-11")
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value(HttpErrorCode.MALFORMED_REQUEST_BODY.code) }
            jsonPath("$.title") { value(HttpErrorCode.MALFORMED_REQUEST_BODY.title) }
            jsonPath("$.status") { value(HttpStatus.BAD_REQUEST.value()) }
            jsonPath("$.retryable") { value(false) }
        }

        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 400 when the request fails validation`() {
        val invalidRequestJson = """
            {
              "items": [],
              "address": {"city": "", "postalCode": "01001", "line1": "Khreshchatyk 1"}
            }
        """.trimIndent()

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-2")
            content = invalidRequestJson
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(HttpErrorCode.VALIDATION_FAILED.code) }
            jsonPath("$.errors[?(@.field == 'items')]") { exists() }
            jsonPath("$.errors[?(@.field == 'address.city')]") { exists() }
        }

        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 409 when reservation reports insufficient stock`() {
        whenever(placeOrderUseCase.execute(any(), any(), any()))
            .thenThrow(InsufficientStockException(1L, RuntimeException("no stock")))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-3")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value(InventoryErrorCode.INSUFFICIENT_STOCK.code) }
            jsonPath("$.title") { value(InventoryErrorCode.INSUFFICIENT_STOCK.title) }
            jsonPath("$.type") { value("urn:ecommerce:error:${InventoryErrorCode.INSUFFICIENT_STOCK.code}") }
            jsonPath("$.instance") { value("/api/v1/orders") }
            jsonPath("$.retryable") { value(false) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns a retryable 503 when the inventory service is unavailable`() {
        whenever(placeOrderUseCase.execute(any(), any(), any()))
            .thenThrow(InventoryUnavailableException(1L, RuntimeException("inventory down")))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-inventory-unavailable")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isServiceUnavailable() }
            header { string(HttpHeaders.RETRY_AFTER, GlobalExceptionHandler.RETRY_AFTER_SECONDS.toString()) }
            jsonPath("$.code") { value(InventoryErrorCode.INVENTORY_UNAVAILABLE.code) }
            jsonPath("$.retryable") { value(true) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns a retryable 503 when the payment service is unavailable`() {
        whenever(placeOrderUseCase.execute(any(), any(), any()))
            .thenThrow(PaymentServiceUnavailableException(1L, RuntimeException("payment service down")))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-payment-service-unavailable")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value(PaymentErrorCode.PAYMENT_SERVICE_UNAVAILABLE.code) }
            jsonPath("$.retryable") { value(true) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns a retryable 503 when the order was changed concurrently`() {
        whenever(placeOrderUseCase.execute(any(), any(), any()))
            .thenThrow(ConcurrentUpdateException(1L, 0L, RuntimeException()))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-concurrent-update")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value(RepositoryErrorCode.CONCURRENT_UPDATE.code) }
            jsonPath("$.retryable") { value(true) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 400 with the external message when a domain rule rejects the input`() {
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(InvalidAddressException("city"))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-4")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(ValueErrorCode.INVALID_ADDRESS.code) }
            jsonPath("$.detail") { value("city is required") }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 500 without its message when an unexpected error occurs`() {
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(IllegalStateException("unexpected error"))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-6")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value(HttpErrorCode.INTERNAL_ERROR.code) }
            jsonPath("$.title") { value(HttpErrorCode.INTERNAL_ERROR.title) }
            jsonPath("$.detail") { doesNotExist() }
            jsonPath("$.retryable") { value(false) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `getOrderById - returns 200 with the order of the customer from the token`() {
        val orderId = 1L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = testOrder(id = orderId, customerId = customerId)
        whenever(getOrderUseCase.execute(orderId, customerId)).thenReturn(order)

        mockMvc.get("/api/v1/orders/{orderId}", orderId) {
            with(clientJwt(customerId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(orderId) }
            jsonPath("$.customerId") { value(customerId.toString()) }
            jsonPath("$.items[0].productId") { value(order.items.first().productId) }
            jsonPath("$.items[0].productName") { value(order.items.first().productName) }
        }

        verify(getOrderUseCase, times(1)).execute(orderId, customerId)
    }

    @Test
    fun `getOrderById - returns 404 when the order does not exist`() {
        val orderId = 99L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        whenever(getOrderUseCase.execute(orderId, customerId)).thenThrow(OrderNotFoundException(orderId))

        mockMvc.get("/api/v1/orders/{orderId}", orderId) {
            with(clientJwt(customerId))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(RepositoryErrorCode.ORDER_NOT_FOUND.code) }
            jsonPath("$.title") { value(RepositoryErrorCode.ORDER_NOT_FOUND.title) }
        }

        verify(getOrderUseCase, times(1)).execute(orderId, customerId)
    }

    @Test
    fun `placeOrder - returns 400 and never calls the use case when Idempotency-Key is missing`() {
        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(HttpErrorCode.MISSING_IDEMPOTENCY_KEY.code) }
        }

        verify(httpIdempotencyService, never()).claim(any(), any(), any())
        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - records the response against the Idempotency-Key on success`() {
        val clientSecret = "secret_123"
        val order = testOrder()
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenReturn(CheckoutResult(order, clientSecret))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-7")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isCreated() }
        }

        verify(httpIdempotencyService, times(1)).complete(eq("key-7"), eq(HttpStatus.CREATED.value()), any())
        verify(httpIdempotencyService, never()).release(any())
    }

    @Test
    fun `placeOrder - records a permanent failure against the Idempotency-Key`() {
        val idempotencyKey = "key-permanent"
        whenever(placeOrderUseCase.execute(any(), any(), any()))
            .thenThrow(InsufficientStockException(1L, RuntimeException("no stock")))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value(InventoryErrorCode.INSUFFICIENT_STOCK.code) }
        }

        verify(httpIdempotencyService, times(1)).complete(eq(idempotencyKey), eq(HttpStatus.CONFLICT.value()), any())
        verify(httpIdempotencyService, never()).release(any())
    }

    @Test
    fun `placeOrder - releases the Idempotency-Key after a failure a retry may fix`() {
        val idempotencyKey = "key-transient"
        whenever(placeOrderUseCase.execute(any(), any(), any()))
            .thenThrow(InventoryUnavailableException(1L, RuntimeException("inventory down")))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value(InventoryErrorCode.INVENTORY_UNAVAILABLE.code) }
        }

        verify(httpIdempotencyService, times(1)).release(idempotencyKey)
        verify(httpIdempotencyService, never()).complete(any(), any(), any())
    }

    @Test
    fun `placeOrder - replays the recorded response without calling the use case`() {
        val idempotencyKey = "key-replay"
        val recordedResponse = """{"code":"ORDER.INSUFFICIENT_STOCK"}"""
        val record = HttpRequestRecord(
            id = idempotencyKey,
            requestHash = "hash",
            httpCode = HttpStatus.CONFLICT.value(),
            response = recordedResponse,
            createdAt = Instant.now(),
            claimedAt = Instant.now(),
            completedAt = Instant.now(),
        )
        whenever(httpIdempotencyService.claim(eq(idempotencyKey), any(), any()))
            .thenReturn(RequestAttempt(RequestResult.CACHED, record))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            content { string(recordedResponse) }
        }

        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 422 when the Idempotency-Key was used with a different request`() {
        val idempotencyKey = "key-reused"
        whenever(httpIdempotencyService.claim(eq(idempotencyKey), any(), any()))
            .thenReturn(RequestAttempt(RequestResult.HASH_MISMATCH))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value(HttpErrorCode.IDEMPOTENCY_KEY_REUSED.code) }
            jsonPath("$.retryable") { value(false) }
        }

        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns a retryable 409 while a request with the same Idempotency-Key is in progress`() {
        val idempotencyKey = "key-in-progress"
        whenever(httpIdempotencyService.claim(eq(idempotencyKey), any(), any()))
            .thenReturn(RequestAttempt(RequestResult.IN_PROGRESS))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            header { string(HttpHeaders.RETRY_AFTER, GlobalExceptionHandler.RETRY_AFTER_SECONDS.toString()) }
            jsonPath("$.code") { value(HttpErrorCode.REQUEST_IN_PROGRESS.code) }
            jsonPath("$.retryable") { value(true) }
        }

        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 500 when the Idempotency-Key can't be claimed`() {
        val idempotencyKey = "key-store-down"
        whenever(httpIdempotencyService.claim(eq(idempotencyKey), any(), any())).thenThrow(IllegalStateException("db down"))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value(HttpErrorCode.INTERNAL_ERROR.code) }
        }

        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 401 before claiming the Idempotency-Key when no token is sent`() {
        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-8")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isUnauthorized() }
            header { exists(HttpHeaders.WWW_AUTHENTICATE) }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value(HttpErrorCode.MISSING_BEARER_TOKEN.code) }
            jsonPath("$.status") { value(HttpStatus.UNAUTHORIZED.value()) }
        }

        verify(httpIdempotencyService, never()).claim(any(), any(), any())
        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 403 when the caller is not a client`() {
        mockMvc.post("/api/v1/orders") {
            with(jwt().authorities(SimpleGrantedAuthority("ROLE_COURIER")))
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-9")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isForbidden() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value(HttpErrorCode.ACCESS_DENIED.code) }
            jsonPath("$.status") { value(HttpStatus.FORBIDDEN.value()) }
        }

        verify(httpIdempotencyService, never()).claim(any(), any(), any())
        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `getOrderById - returns 401 when no token is sent`() {
        mockMvc.get("/api/v1/orders/{orderId}", 1L).andExpect {
            status { isUnauthorized() }
        }

        verify(getOrderUseCase, never()).execute(any(), any())
    }

    @Test
    fun `getOrderById - returns 403 when the caller is not a client`() {
        mockMvc.get("/api/v1/orders/{orderId}", 1L) {
            with(jwt().authorities(SimpleGrantedAuthority("ROLE_ADMIN")))
        }.andExpect {
            status { isForbidden() }
        }

        verify(getOrderUseCase, never()).execute(any(), any())
    }

    @Test
    fun `cancelOrder - returns 200 with the order of the customer from the token as it is after the request`() {
        val orderId = 1L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val order = Order.createExisting(
            id = orderId,
            customerId = customerId,
            items = listOf(testItem()),
            address = testAddress(),
            status = OrderStatus.CANCEL_REQUESTED,
            cancellationReason = CancellationReason.CANCELLED_BY_CUSTOMER,
            createdAt = Instant.now(),
        )
        whenever(cancelOrderUseCase.execute(orderId, customerId)).thenReturn(order)

        mockMvc.post("/api/v1/orders/{orderId}/cancel", orderId) {
            with(clientJwt(customerId))
            header("Idempotency-Key", "key-cancel-1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(orderId) }
            jsonPath("$.status") { value(OrderStatus.CANCEL_REQUESTED.name) }
            jsonPath("$.cancellationReason") { value(CancellationReason.CANCELLED_BY_CUSTOMER.name) }
        }

        verify(cancelOrderUseCase, times(1)).execute(orderId, customerId)
    }

    @Test
    fun `cancelOrder - returns 409 when the order can no longer be cancelled`() {
        val orderId = 1L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        whenever(cancelOrderUseCase.execute(orderId, customerId))
            .thenThrow(OrderNotCancellableException(orderId, OrderStatus.FULFILLED))

        mockMvc.post("/api/v1/orders/{orderId}/cancel", orderId) {
            with(clientJwt(customerId))
            header("Idempotency-Key", "key-cancel-2")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.status") { value(HttpStatus.CONFLICT.value()) }
            jsonPath("$.code") { value(OrderErrorCode.ORDER_NOT_CANCELLABLE.code) }
            jsonPath("$.title") { value(OrderErrorCode.ORDER_NOT_CANCELLABLE.title) }
        }

        verify(cancelOrderUseCase, times(1)).execute(orderId, customerId)
    }

    @Test
    fun `cancelOrder - returns 404 when the order does not exist`() {
        val orderId = 99L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        whenever(cancelOrderUseCase.execute(orderId, customerId)).thenThrow(OrderNotFoundException(orderId))

        mockMvc.post("/api/v1/orders/{orderId}/cancel", orderId) {
            with(clientJwt(customerId))
            header("Idempotency-Key", "key-cancel-3")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(RepositoryErrorCode.ORDER_NOT_FOUND.code) }
        }

        verify(cancelOrderUseCase, times(1)).execute(orderId, customerId)
    }

    @Test
    fun `cancelOrder - returns 403 when the caller is not a client`() {
        mockMvc.post("/api/v1/orders/{orderId}/cancel", 1L) {
            with(jwt().authorities(SimpleGrantedAuthority("ROLE_COURIER")))
            header("Idempotency-Key", "key-cancel-4")
        }.andExpect {
            status { isForbidden() }
        }

        verifyNoInteractions(cancelOrderUseCase)
    }

    @Test
    fun `findCustomerOrders - returns 200 with the requested page of the customer's orders`() {
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val page = 1
        val size = 2
        val orderId = 7L
        val totalElements = 3L
        val order = testOrder(id = orderId, customerId = customerId)
        whenever(findCustomerOrdersUseCase.execute(customerId, page, size)).thenReturn(OrderPage(listOf(order), totalElements))

        mockMvc.get("/api/v1/orders?page={page}&size={size}", page, size) {
            with(clientJwt(customerId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.page") { value(page) }
            jsonPath("$.size") { value(size) }
            jsonPath("$.totalElements") { value(totalElements) }
            jsonPath("$.totalPages") { value(2) }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(orderId) }
            jsonPath("$.items[0].status") { value(order.status.name) }
            jsonPath("$.items[0].totalPrice.amount") { value(20.0) }
            jsonPath("$.items[0].totalPrice.currency") { value("EUR") }
            // one order line of 2 units
            jsonPath("$.items[0].itemCount") { value(2) }
            jsonPath("$.items[0].createdAt") { exists() }
        }

        verify(findCustomerOrdersUseCase, times(1)).execute(customerId, page, size)
    }

    @Test
    fun `findCustomerOrders - asks for the first page of the default size when the request names none`() {
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val firstPage = 0
        val defaultSize = 20
        whenever(findCustomerOrdersUseCase.execute(customerId, firstPage, defaultSize)).thenReturn(OrderPage(emptyList(), 0))

        mockMvc.get("/api/v1/orders") {
            with(clientJwt(customerId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(0) }
            jsonPath("$.totalPages") { value(0) }
        }

        verify(findCustomerOrdersUseCase, times(1)).execute(customerId, firstPage, defaultSize)
    }

    @Test
    fun `findCustomerOrders - returns 400 when the use case rejects the page`() {
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val size = 101
        val firstPage = 0
        val message = "size must be between 1 and 100"
        whenever(findCustomerOrdersUseCase.execute(customerId, firstPage, size))
            .thenThrow(InvalidPageException(firstPage, size, message))

        mockMvc.get("/api/v1/orders?size={size}", size) {
            with(clientJwt(customerId))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(UseCaseErrorCode.INVALID_PAGE.code) }
            jsonPath("$.detail") { value(message) }
        }

        verify(findCustomerOrdersUseCase, times(1)).execute(customerId, firstPage, size)
    }

    @Test
    fun `findCustomerOrders - returns 400 without calling the use case when the page is not a number`() {
        mockMvc.get("/api/v1/orders?page=first") {
            with(clientJwt())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(HttpErrorCode.INVALID_PARAMETER.code) }
        }

        verify(findCustomerOrdersUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `findCustomerOrders - returns 401 when no token is sent`() {
        mockMvc.get("/api/v1/orders").andExpect {
            status { isUnauthorized() }
        }

        verify(findCustomerOrdersUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `findCustomerOrders - returns 403 when the caller is not a client`() {
        mockMvc.get("/api/v1/orders") {
            with(jwt().authorities(SimpleGrantedAuthority("ROLE_COURIER")))
        }.andExpect {
            status { isForbidden() }
        }

        verify(findCustomerOrdersUseCase, never()).execute(any(), any(), any())
    }
}
