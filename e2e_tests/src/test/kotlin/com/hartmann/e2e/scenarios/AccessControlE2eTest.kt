package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.order.AddressRequest
import com.hartmann.e2e.support.api.order.MoneyRequest
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.order.OrderItemRequest
import com.hartmann.e2e.support.api.order.PlaceOrderRequest
import com.hartmann.e2e.support.api.shipping.ShippingApi
import com.hartmann.e2e.support.auth.TestUser
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

/** Who may do what, checked directly against each service (roles by the service itself, ownership by its use cases). */
class AccessControlE2eTest : E2eScenarioTest() {

    private fun placeOrderRequest(productId: Long): PlaceOrderRequest =
        PlaceOrderRequest(
            items = listOf(OrderItemRequest(productId, 1, MoneyRequest(BigDecimal("79.99"), "USD"))),
            address = AddressRequest(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1"),
        )

    @Test
    fun `place order - rejected without a token`() {
        val statusCode = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(placeOrderRequest(3L))
            .post("${DockerComposeStack.orderServiceUrl}/api/v1/orders")
            .statusCode()

        assertThat(statusCode).isEqualTo(HttpURLConnection.HTTP_UNAUTHORIZED)
    }

    @Test
    fun `place order - forbidden to a courier`() {
        val statusCode = OrderApi.placeOrder(DockerComposeStack.orderServiceUrl, TestUser.COURIER1, placeOrderRequest(3L))
            .extract().statusCode()

        assertThat(statusCode).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN)
    }

    @Test
    fun `get order - another customer's order is reported as not found`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val order = E2eSharedSteps.placeOrder(orderUrl, 3L, 1, BigDecimal("79.99"), customer = TestUser.CLIENT1)

        val ownerStatusCode = OrderApi.findOrder(orderUrl, TestUser.CLIENT1, order.id).extract().statusCode()
        val otherCustomerStatusCode = OrderApi.findOrder(orderUrl, TestUser.CLIENT2, order.id).extract().statusCode()

        assertThat(ownerStatusCode).isEqualTo(HttpURLConnection.HTTP_OK)
        // not 403: another customer's order must not even be confirmed to exist
        assertThat(otherCustomerStatusCode).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
    }

    @Test
    fun `shipment - open to every courier without its street until pickup, then the picking courier's only`() {
        val shippingUrl = DockerComposeStack.shippingServiceUrl
        val unitPrice = BigDecimal("79.99")
        val product = E2eSharedSteps.createScenarioProduct(DockerComposeStack.inventoryServiceUrl, unitPrice)
        val firstPage = 0
        val pageSize = 100
        val order = E2eSharedSteps.placeOrderUntilShipmentCreated(
            DockerComposeStack.orderServiceUrl,
            DockerComposeStack.paymentServiceUrl,
            product.id,
            1,
            unitPrice,
            customer = TestUser.CLIENT1,
        )
        val shipmentId = requireNotNull(order.shipment).id

        // customers see the shipment in their order, never in the shipping-service
        val customerStatusCode = ShippingApi.findShipment(shippingUrl, TestUser.CLIENT1, shipmentId).extract().statusCode()
        assertThat(customerStatusCode).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN)

        // before pickup every courier sees it - city and postal code only - and finds it among the available ones
        val beforePickup = ShippingApi.getShipment(shippingUrl, TestUser.COURIER2, shipmentId)
        assertThat(beforePickup.address.city).isEqualTo("Kyiv")
        assertThat(beforePickup.address.line1).isNull()
        val availableIds = ShippingApi.getAvailableShipments(shippingUrl, TestUser.COURIER2, firstPage, pageSize).items.map { it.id }
        assertThat(availableIds).contains(shipmentId)

        // the pickup makes it courier1's: only they see the street and have it in their own list
        ShippingApi.pickup(shippingUrl, TestUser.COURIER1, shipmentId)
        assertThat(ShippingApi.getShipment(shippingUrl, TestUser.COURIER1, shipmentId).address.line1).isEqualTo("Khreshchatyk 1")
        assertThat(ShippingApi.getShipment(shippingUrl, TestUser.COURIER2, shipmentId).address.line1).isNull()
        assertThat(ShippingApi.getCourierActiveShipments(shippingUrl, TestUser.COURIER1, firstPage, pageSize).items.map { it.id }).contains(shipmentId)
        assertThat(ShippingApi.getCourierActiveShipments(shippingUrl, TestUser.COURIER2, firstPage, pageSize).items.map { it.id }).doesNotContain(shipmentId)

        val secondPickupStatusCode = ShippingApi.transition(shippingUrl, TestUser.COURIER2, shipmentId, "pickup")
            .extract().statusCode()
        val otherCourierDeliverStatusCode = ShippingApi.transition(shippingUrl, TestUser.COURIER2, shipmentId, "deliver")
            .extract().statusCode()
        assertThat(secondPickupStatusCode).isEqualTo(HttpURLConnection.HTTP_CONFLICT)
        assertThat(otherCourierDeliverStatusCode).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN)

        // a delivered shipment needs nothing more from the courier, so it leaves their list
        val delivered = ShippingApi.deliver(shippingUrl, TestUser.COURIER1, shipmentId)
        assertThat(delivered.status).isEqualTo("Delivered")
        assertThat(ShippingApi.getCourierActiveShipments(shippingUrl, TestUser.COURIER1, firstPage, pageSize).items.map { it.id }).doesNotContain(shipmentId)
    }

    @Test
    fun `internal endpoints - forbidden to a customer calling them directly`() {
        // the order-service calls these with its own token (client credentials), never with a customer's
        val reservationStatusCode = givenAs(TestUser.CLIENT1)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("{}")
            .post("${DockerComposeStack.inventoryServiceUrl}/api/v1/reservations")
            .statusCode()
        val paymentStatusCode = givenAs(TestUser.CLIENT1)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("{}")
            .post("${DockerComposeStack.paymentServiceUrl}/api/v1/payments")
            .statusCode()

        assertThat(reservationStatusCode).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN)
        assertThat(paymentStatusCode).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN)
    }
}
