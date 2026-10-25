package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.api.common.assertProblem
import com.hartmann.e2e.support.api.order.AddressRequest
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.order.OrderItemRequest
import com.hartmann.e2e.support.api.order.PlaceOrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection

class InsufficientStockE2eTest : E2eScenarioTest() {

    @Test
    fun `place order - rejected synchronously when requested quantity exceeds available stock, nothing reserved and no order created`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl

        val productId = 1L // seeded "Laptop", 10 available
        val product = InventoryApi.getProduct(inventoryUrl, productId)
        val requestedQuantity = product.available + 1
        val firstPage = 0
        val pageSize = 1
        val ordersBefore = OrderApi.findCustomerOrders(orderUrl, TestUser.CLIENT1, firstPage, pageSize).totalElements

        val response = OrderApi.placeOrder(
            orderUrl,
            TestUser.CLIENT1,
            PlaceOrderRequest(
                items = listOf(OrderItemRequest(productId, requestedQuantity)),
                address = AddressRequest(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1"),
            ),
        )

        assertProblem(response, HttpURLConnection.HTTP_CONFLICT, "ORDER.INSUFFICIENT_STOCK")

        val productAfterAttempt = InventoryApi.getProduct(inventoryUrl, productId)
        assertThat(productAfterAttempt.reserved).isEqualTo(product.reserved)
        assertThat(productAfterAttempt.available).isEqualTo(product.available)
        // a failed reservation is not an order: nothing is stored for it
        assertThat(OrderApi.findCustomerOrders(orderUrl, TestUser.CLIENT1, firstPage, pageSize).totalElements).isEqualTo(ordersBefore)
    }
}
