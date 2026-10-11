package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.order.OrderResponse
import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.auth.givenAs
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.util.UUID

class OrderPricingE2eTest : E2eScenarioTest() {

    @Test
    fun `place order - priced by the inventory, whatever price the customer sends`() {
        val unitPrice = BigDecimal("79.99")
        val quantity = 2
        val product = E2eSharedSteps.createScenarioProduct(DockerComposeStack.inventoryServiceUrl, unitPrice)
        // a customer trying to name their own price, as the API once allowed
        val requestBody = """
            {
              "items": [{"productId": ${product.id}, "quantity": $quantity, "price": {"amount": 0.01, "currency": "EUR"}}],
              "address": {"city": "Kyiv", "postalCode": "01001", "line1": "Khreshchatyk 1"}
            }
        """.trimIndent()

        val order = givenAs(TestUser.CLIENT1)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(requestBody)
            .post("${DockerComposeStack.orderServiceUrl}/api/v1/orders")
            .then()
            .statusCode(HttpURLConnection.HTTP_CREATED)
            .extract().`as`(OrderResponse::class.java)

        assertThat(order.items.single().price.amount).isEqualByComparingTo(unitPrice)
        assertThat(order.totalPrice.amount).isEqualByComparingTo(unitPrice.multiply(quantity.toBigDecimal()))
        assertThat(order.totalPrice.currency).isEqualTo("EUR")
    }
}
