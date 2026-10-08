package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.payment.PaymentApi
import com.hartmann.e2e.support.api.shipping.ShippingApi
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ShipmentLostE2eTest : E2eScenarioTest() {

    @Test
    fun `lose shipment - courier loses the goods after pickup and a refund is requested, stock is never returned`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl
        val shippingUrl = DockerComposeStack.shippingServiceUrl

        val productId = 3L
        val quantity = 1
        val product = InventoryApi.getProduct(inventoryUrl, productId)

        val processingOrder =
            E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, paymentUrl, productId, quantity, BigDecimal("79.99"))
        val trackingNumber = requireNotNull(processingOrder.shipment).trackingNumber
        val shipment = ShippingApi.getByTrackingNumber(shippingUrl, TestUser.COURIER1, trackingNumber)

        ShippingApi.pickup(shippingUrl, TestUser.COURIER1, shipment.id) // Created -> In Delivery, holder -> Courier: stock is sold

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterPickup = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterPickup.quantity).isEqualTo(product.quantity - quantity)
            assertThat(productAfterPickup.reserved).isEqualTo(product.reserved)
        }

        val shipmentAfterLose = ShippingApi.lose(shippingUrl, TestUser.COURIER1, shipment.id) // In Delivery -> Lost
        assertThat(shipmentAfterLose.status).isEqualTo("Lost")

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterLoss = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, processingOrder.id)
            assertThat(orderAfterLoss.status).isEqualTo("CANCELLED")
            assertThat(orderAfterLoss.cancellationReason).isEqualTo("SHIPMENT_LOST")
            assertThat(orderAfterLoss.refund?.confirmedAt).isNotNull()
        }

        val refunds = PaymentApi.getRefundsByOrderId(paymentUrl, processingOrder.id)
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().status).isEqualTo("SUCCEEDED")

        // the goods are gone for good - unlike a customer cancellation after pickup, there's no
        // courier return leg here, so stock must stay decremented permanently
        val productAfterLoss = InventoryApi.getProduct(inventoryUrl, productId)
        assertThat(productAfterLoss.quantity).isEqualTo(product.quantity - quantity)
    }
}
