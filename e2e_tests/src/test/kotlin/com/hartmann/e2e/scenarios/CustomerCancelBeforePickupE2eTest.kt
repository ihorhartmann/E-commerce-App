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

class CustomerCancelBeforePickupE2eTest : E2eScenarioTest() {

    @Test
    fun `cancel shipment - before pickup releases the reservation immediately and requests a refund`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl
        val shippingUrl = DockerComposeStack.shippingServiceUrl

        val productId = 3L
        val quantity = 1
        val product = InventoryApi.getProduct(inventoryUrl, productId)

        val processingOrder = E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, paymentUrl, productId, quantity, BigDecimal("79.99"))
        val trackingNumber = requireNotNull(processingOrder.shipment).trackingNumber
        val shipment = ShippingApi.getByTrackingNumber(shippingUrl, TestUser.COURIER1, trackingNumber)
        assertThat(shipment.status).isEqualTo("Created") // not picked up yet

        ShippingApi.cancel(shippingUrl, shipment.id)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterCancel = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, processingOrder.id)
            assertThat(orderAfterCancel.status).isEqualTo("CANCELLED")
            assertThat(orderAfterCancel.cancellationReason).isEqualTo("CANCELLED_BY_CUSTOMER")
            assertThat(orderAfterCancel.refund?.confirmedAt).isNotNull()
        }

        val refunds = PaymentApi.getRefundsByOrderId(paymentUrl, processingOrder.id)
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().status).isEqualTo("SUCCEEDED")

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterCancel = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterCancel.reserved).isEqualTo(product.reserved)
            assertThat(productAfterCancel.available).isEqualTo(product.available)
        }
    }
}
