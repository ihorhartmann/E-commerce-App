package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.shipping.ShippingApi
import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.auth.TokenProvider
import com.hartmann.e2e.support.auth.givenAs
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.util.UUID

/**
 * Smoke test of the gateway's routes with a Bearer token, one request per route. The business scenarios call the
 * services directly; the browser path (login form, session cookie, CSRF) is not covered by the e2e tests.
 */
class GatewayRoutesE2eTest : E2eScenarioTest() {
    private val gatewayUrl = DockerComposeStack.gatewayUrl

    @Test
    fun `products - the catalog is public through the gateway`() {
        val product = InventoryApi.getProduct(gatewayUrl, 1L)

        assertThat(product.name).isEqualTo("Laptop")
    }

    @Test
    fun `products - the catalog list is public through the gateway`() {
        val products = InventoryApi.getProducts(gatewayUrl, 0, 1)

        assertThat(products.items.map { it.name }).containsExactly("Laptop")
    }

    @Test
    fun `orders - the customer's own orders listed through the gateway`() {
        val placedOrder = E2eSharedSteps.placeOrder(gatewayUrl, 3L, 1, BigDecimal("79.99"), customer = TestUser.CLIENT1)

        val orders = OrderApi.findCustomerOrders(gatewayUrl, TestUser.CLIENT1, 0, 1)

        assertThat(orders.items.map { it.id }).containsExactly(placedOrder.id)
    }

    @Test
    fun `orders - placed and read through the gateway`() {
        // a Bearer request needs no CSRF token - only one riding on the browser's session cookie does
        val placedOrder = E2eSharedSteps.placeOrder(gatewayUrl, 3L, 1, BigDecimal("79.99"), customer = TestUser.CLIENT1)

        val order = OrderApi.getOrder(gatewayUrl, TestUser.CLIENT1, placedOrder.id)

        assertThat(order.id).isEqualTo(placedOrder.id)
        assertThat(order.customerId).isEqualTo(TokenProvider.subject(TestUser.CLIENT1))
    }

    @Test
    fun `shipments - routed to the shipping-service`() {
        val response = ShippingApi.findByTrackingNumber(gatewayUrl, TestUser.COURIER1, "TRK-UNKNOWN")

        // the shipping-service's own answer, not a gateway 404 for an unknown route
        response.statusCode(HttpURLConnection.HTTP_NOT_FOUND)
        assertThat(response.extract().path<String>("error")).isEqualTo("shipment not found")
    }

    @Test
    fun `me - the gateway's own endpoint reads the caller from the Bearer token`() {
        val response = givenAs(TestUser.CLIENT1)
            .get("$gatewayUrl/api/v1/me")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract()

        assertThat(response.path<String>("id")).isEqualTo(TokenProvider.subject(TestUser.CLIENT1).toString())
        assertThat(response.path<String>("username")).isEqualTo(TestUser.CLIENT1.username)
        assertThat(response.path<List<String>>("roles")).contains("CLIENT")
    }

    @Test
    fun `orders - rejected by the gateway itself without a token`() {
        val statusCode = given().get("$gatewayUrl/api/v1/orders/1").statusCode()

        assertThat(statusCode).isEqualTo(HttpURLConnection.HTTP_UNAUTHORIZED)
    }

    @Test
    fun `payments and reservations - not routed by the gateway`() {
        // only the order-service calls them, inside the network; even an admin gets no route to them from outside
        val paymentStatusCode = givenAs(TestUser.ADMIN).get("$gatewayUrl/api/v1/payments/1").statusCode()
        val reservationStatusCode = givenAs(TestUser.ADMIN)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("{}")
            .post("$gatewayUrl/api/v1/reservations")
            .statusCode()

        assertThat(paymentStatusCode).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
        assertThat(reservationStatusCode).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
    }
}
