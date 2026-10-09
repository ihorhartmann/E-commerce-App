package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.auth.TokenProvider
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.shipping.ShippingApi
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HappyPathFulfillmentE2eTest : E2eScenarioTest() {

    @Test
    fun `place order - full fulfillment across all four services`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl
        val shippingUrl = DockerComposeStack.shippingServiceUrl

        val quantity = 2
        val unitPrice = BigDecimal("79.99")
        val product = E2eSharedSteps.createScenarioProduct(inventoryUrl, unitPrice)
        val productId = product.id

        val processingOrder = E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, paymentUrl, productId, quantity, unitPrice)
        // the order belongs to whoever placed it - taken from the token, not from the request
        assertThat(processingOrder.customerId).isEqualTo(TokenProvider.subject(TestUser.CLIENT1))

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterConfirmation = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterConfirmation.reserved).isEqualTo(product.reserved + quantity)
            assertThat(productAfterConfirmation.available).isEqualTo(product.available - quantity)
        }

        val shipment = ShippingApi.getShipment(shippingUrl, TestUser.COURIER1, requireNotNull(processingOrder.shipment).id)
        assertThat(shipment.status).isEqualTo("Created")

        ShippingApi.pickup(shippingUrl, TestUser.COURIER1, shipment.id) // Created -> In Delivery: FulfillReservationCommand sells the stock

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterPickup = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterPickup.quantity).isEqualTo(product.quantity - quantity)
            assertThat(productAfterPickup.reserved).isEqualTo(product.reserved)
        }

        ShippingApi.deliver(shippingUrl, TestUser.COURIER1, shipment.id) // In Delivery -> Delivered

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterDelivery = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, processingOrder.id)
            assertThat(orderAfterDelivery.status).isEqualTo("FULFILLED")
            assertThat(orderAfterDelivery.shipment?.deliveredAt).isNotNull()
        }
    }
}
