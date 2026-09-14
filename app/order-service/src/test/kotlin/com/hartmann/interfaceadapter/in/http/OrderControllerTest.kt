package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.entities.exception.DomainException
import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.`in`.usecase.GetOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import com.hartmann.interfaceadapter.`in`.http.idempotency.HttpIdempotencyService
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestAttempt
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(OrderController::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var placeOrderUseCase: PlaceOrderUseCase

    @MockitoBean
    private lateinit var getOrderUseCase: GetOrderUseCase

    // IdempotencyFilter runs ahead of every controller in this slice too, so it needs a bean here
    // regardless of whether a given test cares about idempotency itself. Stubbing this
    // unconditionally in @BeforeEach is harmless for the GET tests too - Spring's @WebMvcTest
    // doesn't enforce strict/unnecessary-stubbing checks the way MockitoExtension does.
    @MockitoBean
    private lateinit var httpIdempotencyService: HttpIdempotencyService

    @BeforeEach
    fun stubIdempotencyStart() {
        whenever(httpIdempotencyService.claim(any(), any(), any())).thenReturn(RequestAttempt.claimed())
    }

    private fun testAddress() = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

    private fun testItem() = OrderItem.createExisting(1, 1, 1, 2, Money(BigDecimal("10.00"), Currency.USD))

    private fun testOrder(id: Long = 1) = Order.createExisting(
        id = id,
        userId = 1,
        items = listOf(testItem()),
        address = testAddress(),
        status = OrderStatus.PLACED,
        createdAt = Instant.now(),
    )

    private val validPlaceOrderRequestJson: String = """
        {
          "userId": 1,
          "items": [{"productId": 1, "quantity": 2, "price": {"amount": 10.00, "currency": "USD"}}],
          "address": {"city": "Kyiv", "postalCode": "01001", "line1": "Khreshchatyk 1"}
        }
    """.trimIndent()

    @Test
    fun `placeOrder - returns 201 with the created order`() {
        val clientSecret = "secret_123"
        val order = testOrder()
        whenever(placeOrderUseCase.execute(any())).thenReturn(CheckoutResult(order, clientSecret))

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-1")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(order.id) }
            jsonPath("$.clientSecret") { value(clientSecret) }
        }

        verify(placeOrderUseCase, times(1)).execute(any())
    }

    @Test
    fun `placeOrder - returns 400 when the request fails validation`() {
        val invalidRequestJson = """
            {
              "userId": -1,
              "items": [],
              "address": {"city": "", "postalCode": "01001", "line1": "Khreshchatyk 1"}
            }
        """.trimIndent()

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-2")
            content = invalidRequestJson
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("Validation failed") }
            jsonPath("$.details.userId") { exists() }
            jsonPath("$.details.items") { exists() }
            jsonPath("$.details['address.city']") { exists() }
        }

        verify(placeOrderUseCase, never()).execute(any())
    }

    @Test
    fun `placeOrder - returns 409 when reservation reports insufficient stock`() {
        val message = "no stock"
        whenever(placeOrderUseCase.execute(any())).thenThrow(InsufficientStockException(message))

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-3")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any())
    }

    @Test
    fun `placeOrder - returns 400 when a domain rule is violated`() {
        val message = "invalid order"
        whenever(placeOrderUseCase.execute(any())).thenAnswer { throw DomainException(message) }

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-4")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any())
    }

    @Test
    fun `placeOrder - returns 409 when the order is in an unexpected state`() {
        val message = "cannot place order"
        whenever(placeOrderUseCase.execute(any())).thenThrow(IllegalStateException(message))

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-5")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value(message) }
        }

        verify(placeOrderUseCase, times(1)).execute(any())
    }

    @Test
    fun `placeOrder - returns 500 when an unexpected error occurs`() {
        whenever(placeOrderUseCase.execute(any())).thenThrow(RuntimeException("unexpected error"))

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-6")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.error") { value("Internal server error") }
        }

        verify(placeOrderUseCase, times(1)).execute(any())
    }

    @Test
    fun `getOrderById - returns 200 with the order`() {
        val orderId = 1L
        val order = testOrder(id = orderId)
        whenever(getOrderUseCase.execute(orderId)).thenReturn(order)

        mockMvc.get("/api/v1/orders/{orderId}", orderId).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(orderId) }
        }

        verify(getOrderUseCase, times(1)).execute(orderId)
    }

    @Test
    fun `getOrderById - returns 404 when the order does not exist`() {
        val orderId = 99L
        val message = "Order [ID=$orderId] was not found"
        whenever(getOrderUseCase.execute(orderId)).thenThrow(OrderNotFoundException(orderId))

        mockMvc.get("/api/v1/orders/{orderId}", orderId).andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value(message) }
        }

        verify(getOrderUseCase, times(1)).execute(orderId)
    }

    @Test
    fun `placeOrder - returns 400 and never calls the use case when Idempotency-Key is missing`() {
        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isBadRequest() }
        }

        verify(placeOrderUseCase, never()).execute(any())
    }

    @Test
    fun `placeOrder - records the response against the Idempotency-Key on success`() {
        val clientSecret = "secret_123"
        val order = testOrder()
        whenever(placeOrderUseCase.execute(any())).thenReturn(CheckoutResult(order, clientSecret))

        mockMvc.post("/api/v1/orders") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "key-7")
            content = validPlaceOrderRequestJson
        }.andExpect {
            status { isCreated() }
        }

        verify(httpIdempotencyService, times(1)).complete(eq("key-7"), eq(HttpStatus.CREATED.value()), any())
    }
}
