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
    fun `shipment - seen by its customer and couriers only, moved on by the assigned courier only`() {
        val shippingUrl = DockerComposeStack.shippingServiceUrl
        val order = E2eSharedSteps.placeOrderUntilShipmentCreated(
            DockerComposeStack.orderServiceUrl,
            DockerComposeStack.paymentServiceUrl,
            3L,
            1,
            BigDecimal("79.99"),
            customer = TestUser.CLIENT1,
        )
        val trackingNumber = requireNotNull(order.shipment).trackingNumber

        val ownerStatusCode = ShippingApi.findByTrackingNumber(shippingUrl, TestUser.CLIENT1, trackingNumber)
            .extract().statusCode()
        val otherCustomerStatusCode = ShippingApi.findByTrackingNumber(shippingUrl, TestUser.CLIENT2, trackingNumber)
            .extract().statusCode()
        assertThat(ownerStatusCode).isEqualTo(HttpURLConnection.HTTP_OK)
        assertThat(otherCustomerStatusCode).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)

        // any courier finds it - that's how they pick one up; the pickup makes it courier1's
        val shipment = ShippingApi.getByTrackingNumber(shippingUrl, TestUser.COURIER2, trackingNumber)
        ShippingApi.pickup(shippingUrl, TestUser.COURIER1, shipment.id)

        val otherCourierStatusCode = ShippingApi.transition(shippingUrl, TestUser.COURIER2, shipment.id, "deliver")
            .extract().statusCode()
        assertThat(otherCourierStatusCode).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN)
        assertThat(ShippingApi.getByTrackingNumber(shippingUrl, TestUser.COURIER1, trackingNumber).status)
            .isEqualTo("In Delivery")

        val delivered = ShippingApi.deliver(shippingUrl, TestUser.COURIER1, shipment.id)
        assertThat(delivered.status).isEqualTo("Delivered")
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
