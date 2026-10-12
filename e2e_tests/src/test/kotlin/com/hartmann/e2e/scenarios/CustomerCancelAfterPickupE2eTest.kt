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

class CustomerCancelAfterPickupE2eTest : E2eScenarioTest() {

    @Test
    fun `cancel order - after pickup restocks only once the courier physically returns the goods`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl
        val shippingUrl = DockerComposeStack.shippingServiceUrl

        val quantity = 1
        val product = E2eSharedSteps.createScenarioProduct(inventoryUrl, BigDecimal("79.99"))
        val productId = product.id

        val processingOrder = E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, paymentUrl, productId, quantity)
        val shipment = ShippingApi.getShipment(shippingUrl, TestUser.COURIER1, requireNotNull(processingOrder.shipment).id)

        ShippingApi.pickup(shippingUrl, TestUser.COURIER1, shipment.id) // Created -> In Delivery, holder -> Courier: stock is sold

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterPickup = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterPickup.quantity).isEqualTo(product.quantity - quantity)
            assertThat(productAfterPickup.reserved).isEqualTo(product.reserved)
        }

        OrderApi.cancelOrder(orderUrl, TestUser.CLIENT1, processingOrder.id)

        // In Delivery -> Cancelled, still held by the courier, who now has to bring the goods back
        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val shipmentAfterCancel = ShippingApi.getShipment(shippingUrl, TestUser.COURIER1, shipment.id)
            assertThat(shipmentAfterCancel.status).isEqualTo("Cancelled")
            assertThat(shipmentAfterCancel.cancellationReason).isEqualTo("OrderCancelled")
            assertThat(shipmentAfterCancel.holder).isEqualTo("Courier")
        }

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterCancel = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, processingOrder.id)
            assertThat(orderAfterCancel.status).isEqualTo("CANCEL_REQUESTED")
            assertThat(orderAfterCancel.cancellationReason).isEqualTo("CANCELLED_BY_CUSTOMER")
            assertThat(orderAfterCancel.refund?.requestedAt).isNotNull()
        }

        // the stock was already sold at pickup, so cancelling must NOT restock until the courier
        // physically brings the goods back
        val productBeforeReturn = InventoryApi.getProduct(inventoryUrl, productId)
        assertThat(productBeforeReturn.quantity).isEqualTo(product.quantity - quantity)

        ShippingApi.confirmReturnedToInventory(shippingUrl, TestUser.COURIER1, shipment.id)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterReturn = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterReturn.quantity).isEqualTo(product.quantity) // RestockCommand brought it back
        }

        // both halves - the physical return above and the refund - must complete before CANCELLED
        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterReturn = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, processingOrder.id)
            assertThat(orderAfterReturn.status).isEqualTo("CANCELLED")
            assertThat(orderAfterReturn.refund?.confirmedAt).isNotNull()
        }

        val refunds = PaymentApi.getRefundsByOrderId(paymentUrl, processingOrder.id)
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().status).isEqualTo("SUCCEEDED")
    }
}
