package com.hartmann.interfaceadapter.out.http.inventory

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.ReservedItem
import com.hartmann.interfaceadapter.out.http.inventory.exception.InsufficientStockException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InvalidInventoryResponseException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryFailureException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryRequestRejectedException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryUnavailableException
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.math.BigDecimal

private const val RESERVATIONS_URL = "http://inventory/api/v1/reservations"

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class HttpInventoryClientTest {
    private val restClientBuilder = RestClient.builder().baseUrl("http://inventory")
    private val server = MockRestServiceServer.bindTo(restClientBuilder).build()
    private val client = HttpInventoryClient(restClientBuilder.build())

    private fun respond(status: HttpStatus, body: String, contentType: MediaType = MediaType.APPLICATION_PROBLEM_JSON) {
        server.expect(requestTo(RESERVATIONS_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(status).contentType(contentType).body(body))
    }

    @Test
    fun `reserve - returns the reserved items`() {
        val productId = 5L
        val name = "Keyboard"
        val quantity = 2
        val price = Money(BigDecimal("10.00"), Currency.EUR)
        respond(
            HttpStatus.CREATED,
            """{"items":[{"productId":$productId,"name":"$name","quantity":$quantity,""" +
                """"price":{"amount":${price.amount},"currency":"${price.currency}"}}]}""",
            MediaType.APPLICATION_JSON,
        )

        val actual = client.reserve(1L, listOf(RequestedItem(productId, quantity)))

        assertEquals(listOf(ReservedItem(productId, name, quantity, price)), actual)
        server.verify()
    }

    @Test
    fun `reserve - throws InsufficientStockException when inventory reports insufficient stock`() {
        val orderId = 1L
        respond(HttpStatus.CONFLICT, """{"status":409,"code":"INVENTORY.INSUFFICIENT_STOCK","retryable":false}""")

        val exception = assertThrows<InsufficientStockException> { client.reserve(orderId, listOf(RequestedItem(5, 2))) }

        assertEquals(orderId, exception.orderId)
        server.verify()
    }

    @Test
    fun `reserve - throws InventoryUnavailableException when inventory asks to retry, even with a 409`() {
        respond(HttpStatus.CONFLICT, """{"status":409,"code":"INVENTORY.REQUEST_IN_PROGRESS","retryable":true}""")

        assertThrows<InventoryUnavailableException> { client.reserve(1L, listOf(RequestedItem(5, 2))) }

        server.verify()
    }

    @Test
    fun `reserve - throws InventoryFailureException with inventory's code for a 5xx it doesn't ask to retry`() {
        val inventoryCode = "INVENTORY.DATABASE_FAILURE"
        respond(HttpStatus.INTERNAL_SERVER_ERROR, """{"status":500,"code":"$inventoryCode","retryable":false}""")

        val exception = assertThrows<InventoryFailureException> { client.reserve(1L, listOf(RequestedItem(5, 2))) }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), exception.status)
        assertEquals(inventoryCode, exception.inventoryCode)
        server.verify()
    }

    @Test
    fun `reserve - throws InventoryFailureException for a 5xx whose body is no ProblemDetail`() {
        respond(HttpStatus.BAD_GATEWAY, "<html>Bad Gateway</html>", MediaType.TEXT_HTML)

        val exception = assertThrows<InventoryFailureException> { client.reserve(1L, listOf(RequestedItem(5, 2))) }

        assertEquals(null, exception.inventoryCode)
        // the body couldn't be read as a ProblemDetail: that failure stays on the response exception
        assertEquals(1, exception.cause!!.suppressed.size)
        server.verify()
    }

    @Test
    fun `reserve - throws InventoryRequestRejectedException for any other 4xx`() {
        val inventoryCode = "INVENTORY.INVALID_RESERVATION"
        respond(HttpStatus.BAD_REQUEST, """{"status":400,"code":"$inventoryCode","retryable":false}""")

        val exception = assertThrows<InventoryRequestRejectedException> { client.reserve(1L, listOf(RequestedItem(5, 2))) }

        assertEquals(HttpStatus.BAD_REQUEST.value(), exception.status)
        assertEquals(inventoryCode, exception.inventoryCode)
        server.verify()
    }

    @Test
    fun `reserve - throws InvalidInventoryResponseException when the reserved items break a domain rule`() {
        respond(
            HttpStatus.CREATED,
            """{"items":[{"productId":5,"name":"Keyboard","quantity":2,"price":{"amount":10.00,"currency":"JPY"}}]}""",
            MediaType.APPLICATION_JSON,
        )

        assertThrows<InvalidInventoryResponseException> { client.reserve(1L, listOf(RequestedItem(5, 2))) }

        server.verify()
    }
}
