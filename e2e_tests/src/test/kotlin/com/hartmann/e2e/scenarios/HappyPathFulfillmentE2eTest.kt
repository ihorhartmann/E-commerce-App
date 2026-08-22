package com.hartmann.e2e.scenarios

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

        val productId = 3L // seeded "Mouse", 40 available
        val quantity = 2
        val unitPrice = BigDecimal("79.99")
        val product = InventoryApi.getProduct(inventoryUrl, productId)

        val processingOrder = E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, paymentUrl, productId, quantity, unitPrice)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterConfirmation = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterConfirmation.reserved).isEqualTo(product.reserved + quantity)
            assertThat(productAfterConfirmation.available).isEqualTo(product.available - quantity)
        }

        val trackingNumber = requireNotNull(processingOrder.shipment).trackingNumber
        val shipment = ShippingApi.getByTrackingNumber(shippingUrl, trackingNumber)
        assertThat(shipment.status).isEqualTo("Created")

        ShippingApi.pickup(shippingUrl, shipment.id) // Created -> In Delivery: FulfillReservationCommand sells the stock

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterPickup = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterPickup.quantity).isEqualTo(product.quantity - quantity)
            assertThat(productAfterPickup.reserved).isEqualTo(product.reserved)
        }

        ShippingApi.deliver(shippingUrl, shipment.id) // In Delivery -> Delivered

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterDelivery = OrderApi.getOrder(orderUrl, processingOrder.id)
            assertThat(orderAfterDelivery.status).isEqualTo("FULFILLED")
            assertThat(orderAfterDelivery.shipment?.deliveredAt).isNotNull()
        }
    }
}
