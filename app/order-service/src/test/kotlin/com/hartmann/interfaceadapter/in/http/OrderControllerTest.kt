package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.entities.exception.DomainException
import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RequestedItem
import com.hartmann.frameworks.config.SecurityConfig
import com.hartmann.interfaceadapter.`in`.http.idempotency.HttpIdempotencyService
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestAttempt
import com.hartmann.usecase.port.`in`.usecase.FindCustomerOrdersUseCase
import com.hartmann.usecase.port.`in`.usecase.GetOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.usecase.port.out.internal.exception.InventoryUnavailableException
import com.hartmann.usecase.port.out.internal.exception.PaymentServiceUnavailableException
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import com.hartmann.usecase.port.out.repository.exception.ConcurrentUpdateException
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
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

    private fun testItem() = OrderItem.createExisting(1, 1, 1, 2, Money(BigDecimal("10.00"), Currency.EUR))

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
            jsonPath("$.error") { value("Malformed request body") }
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
            jsonPath("$.error") { value("Validation failed") }
            jsonPath("$.details.items") { exists() }
            jsonPath("$.details['address.city']") { exists() }
        }

        verify(placeOrderUseCase, never()).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 409 when reservation reports insufficient stock`() {
        val message = "no stock"
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(InsufficientStockException(message))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-3")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 503 when the inventory service is unavailable`() {
        val message = "inventory down"
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(InventoryUnavailableException(message))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-inventory-unavailable")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 503 when the payment service is unavailable`() {
        val message = "payment service down"
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(PaymentServiceUnavailableException(message))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-payment-service-unavailable")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 503 when the order was changed concurrently`() {
        val message = "order changed concurrently"
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(ConcurrentUpdateException(message, RuntimeException()))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-concurrent-update")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 400 when a domain rule is violated`() {
        val message = "invalid order"
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenAnswer { throw DomainException(message) }

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-4")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 409 when the order is in an unexpected state`() {
        val message = "cannot place order"
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(IllegalStateException(message))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-5")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any(), any(), any())
    }

    @Test
    fun `placeOrder - returns 500 when an unexpected error occurs`() {
        whenever(placeOrderUseCase.execute(any(), any(), any())).thenThrow(RuntimeException("unexpected error"))

        mockMvc.post("/api/v1/orders") {
            with(clientJwt())
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-6")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.error") { value("Internal server error") }
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
        }

        verify(getOrderUseCase, times(1)).execute(orderId, customerId)
    }

    @Test
    fun `getOrderById - returns 404 when the order does not exist`() {
        val orderId = 99L
        val customerId = UUID.fromString("0192f1d2-7c4b-7000-8000-000000000001")
        val message = "Order [ID=$orderId] was not found"
        whenever(getOrderUseCase.execute(orderId, customerId)).thenThrow(OrderNotFoundException(orderId))

        mockMvc.get("/api/v1/orders/{orderId}", orderId) {
            with(clientJwt(customerId))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value(message) }
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
        }

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
    }

    @Test
    fun `placeOrder - returns 401 before claiming the Idempotency-Key when no token is sent`() {
        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-8")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isUnauthorized() }
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
            jsonPath("$.items[0].itemCount") { value(order.items.size) }
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
        whenever(findCustomerOrdersUseCase.execute(customerId, firstPage, size)).thenThrow(IllegalArgumentException(message))

        mockMvc.get("/api/v1/orders?size={size}", size) {
            with(clientJwt(customerId))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value(message) }
        }

        verify(findCustomerOrdersUseCase, times(1)).execute(customerId, firstPage, size)
    }

    @Test
    fun `findCustomerOrders - returns 400 without calling the use case when the page is not a number`() {
        mockMvc.get("/api/v1/orders?page=first") {
            with(clientJwt())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("Invalid value for page") }
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
