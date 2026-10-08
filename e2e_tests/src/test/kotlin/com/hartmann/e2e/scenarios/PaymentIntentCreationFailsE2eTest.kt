package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.api.order.AddressRequest
import com.hartmann.e2e.support.api.inventory.CreateProductRequest
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.inventory.InventoryMoneyDto
import com.hartmann.e2e.support.api.order.MoneyRequest
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.order.OrderItemRequest
import com.hartmann.e2e.support.api.order.PlaceOrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.HttpURLConnection

class PaymentIntentCreationFailsE2eTest : E2eScenarioTest() {

    @Test
    fun `place order - PaymentIntent creation fails synchronously below Stripe's minimum charge, reservation released`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl

        // Stripe synchronously rejects PaymentIntent creation for amounts below its USD minimum
        // charge ($0.50) - a real, deterministic trigger for submitPayment's synchronous failure
        // path that needs no container/network manipulation.
        val belowStripeMinimum = BigDecimal("0.10")
        val product = InventoryApi.createProduct(
            inventoryUrl,
            CreateProductRequest(
                name = "E2E cheap item",
                description = "priced below Stripe's minimum charge on purpose",
                price = InventoryMoneyDto(belowStripeMinimum, "USD"),
                quantity = 5,
            ),
        )

        val statusCode = OrderApi.placeOrder(
            orderUrl,
            TestUser.CLIENT1,
            PlaceOrderRequest(
                items = listOf(OrderItemRequest(product.id, 1, MoneyRequest(belowStripeMinimum, "USD"))),
                address = AddressRequest(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1"),
            ),
        ).extract().statusCode()

        assertThat(statusCode).isEqualTo(HttpURLConnection.HTTP_CONFLICT)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterFailure = InventoryApi.getProduct(inventoryUrl, product.id)
            assertThat(productAfterFailure.available).isEqualTo(product.available)
            assertThat(productAfterFailure.reserved).isZero()
        }
    }
}
