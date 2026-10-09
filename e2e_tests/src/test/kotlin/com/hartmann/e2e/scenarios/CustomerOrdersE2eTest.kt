package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CustomerOrdersE2eTest : E2eScenarioTest() {

    @Test
    fun `findCustomerOrders - a customer sees only their own orders, newest first`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val productId = 3L // seeded "Mouse"
        val unitPrice = BigDecimal("79.99")
        val firstPage = 0
        val pageSize = 100
        val firstOrder = E2eSharedSteps.placeOrder(orderUrl, productId, 1, unitPrice, customer = TestUser.CLIENT1)
        val secondOrder = E2eSharedSteps.placeOrder(orderUrl, productId, 2, unitPrice, customer = TestUser.CLIENT1)
        val otherCustomerOrder = E2eSharedSteps.placeOrder(orderUrl, productId, 1, unitPrice, customer = TestUser.CLIENT2)

        // the newest orders are on the first page, whatever other scenarios have placed before
        val client1Orders = OrderApi.findCustomerOrders(orderUrl, TestUser.CLIENT1, firstPage, pageSize)
        val client2Orders = OrderApi.findCustomerOrders(orderUrl, TestUser.CLIENT2, firstPage, pageSize)

        val client1OrderIds = client1Orders.items.map { it.id }
        assertThat(client1OrderIds.take(2)).containsExactly(secondOrder.id, firstOrder.id)
        assertThat(client1OrderIds).doesNotContain(otherCustomerOrder.id)
        assertThat(client2Orders.items.map { it.id }).contains(otherCustomerOrder.id)
            .doesNotContain(firstOrder.id, secondOrder.id)

        val secondOrderSummary = client1Orders.items.first()
        assertThat(secondOrderSummary.totalPrice.amount).isEqualByComparingTo(secondOrder.totalPrice.amount)
        assertThat(secondOrderSummary.itemCount).isEqualTo(secondOrder.items.size)
    }

    @Test
    fun `findCustomerOrders - pages through the customer's orders`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val productId = 3L // seeded "Mouse"
        val quantity = 1
        val unitPrice = BigDecimal("79.99")
        val pageSize = 1
        val olderOrder = E2eSharedSteps.placeOrder(orderUrl, productId, quantity, unitPrice, customer = TestUser.CLIENT1)
        val newerOrder = E2eSharedSteps.placeOrder(orderUrl, productId, quantity, unitPrice, customer = TestUser.CLIENT1)

        val firstPage = OrderApi.findCustomerOrders(orderUrl, TestUser.CLIENT1, 0, pageSize)
        val secondPage = OrderApi.findCustomerOrders(orderUrl, TestUser.CLIENT1, 1, pageSize)

        assertThat(firstPage.items.map { it.id }).containsExactly(newerOrder.id)
        assertThat(secondPage.items.map { it.id }).containsExactly(olderOrder.id)
        // one order per page
        assertThat(firstPage.totalPages).isEqualTo(firstPage.totalElements)
    }
}
