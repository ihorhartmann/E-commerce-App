package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.api.order.AddressRequest
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.MoneyRequest
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.order.OrderItemRequest
import com.hartmann.e2e.support.api.order.PlaceOrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.HttpURLConnection

class InsufficientStockE2eTest : E2eScenarioTest() {

    @Test
    fun `place order - rejected synchronously when requested quantity exceeds available stock, nothing reserved`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl

        val productId = 1L // seeded "Laptop", 10 available
        val product = InventoryApi.getProduct(inventoryUrl, productId)
        val requestedQuantity = product.available + 1

        val statusCode = OrderApi.placeOrder(
            orderUrl,
            PlaceOrderRequest(
                userId = 1,
                items = listOf(OrderItemRequest(productId, requestedQuantity, MoneyRequest(BigDecimal("1299.99"), "USD"))),
                address = AddressRequest(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1"),
            ),
        ).extract().statusCode()

        assertThat(statusCode).isEqualTo(HttpURLConnection.HTTP_CONFLICT)

        val productAfterAttempt = InventoryApi.getProduct(inventoryUrl, productId)
        assertThat(productAfterAttempt.reserved).isEqualTo(product.reserved)
        assertThat(productAfterAttempt.available).isEqualTo(product.available)
    }
}
