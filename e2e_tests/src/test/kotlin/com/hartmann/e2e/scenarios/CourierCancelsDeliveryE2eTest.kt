package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.common.assertProblem
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.order.OrderResponse
import com.hartmann.e2e.support.api.payment.PaymentApi
import com.hartmann.e2e.support.api.shipping.ShippingApi
import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.HttpURLConnection

/** The courier, not the customer, ends the delivery: the goods couldn't be handed over at the door. */
class CourierCancelsDeliveryE2eTest : E2eScenarioTest() {

    /** Places and pays an order, and has courier1 pick its shipment up; waits until the order knows about the pickup. */
    private fun orderInDelivery(productId: Long): OrderResponse {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val processingOrder =
            E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, DockerComposeStack.paymentServiceUrl, productId, 1)
        ShippingApi.pickup(DockerComposeStack.shippingServiceUrl, TestUser.COURIER1, requireNotNull(processingOrder.shipment).id)

        lateinit var orderInDelivery: OrderResponse
        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            orderInDelivery = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, processingOrder.id)
            assertThat(orderInDelivery.shipment?.pickedUpAt).isNotNull()
        }
        return orderInDelivery
    }

    @Test
    fun `cancel shipment - recipient unavailable fails the delivery, refunds the customer and restocks the returned goods`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl
        val shippingUrl = DockerComposeStack.shippingServiceUrl
        val reason = "RecipientUnavailable"
        val product = E2eSharedSteps.createScenarioProduct(inventoryUrl, BigDecimal("79.99"))
        val order = orderInDelivery(product.id)
        val shipmentId = requireNotNull(order.shipment).id

        // only the courier who has the goods may say they couldn't be handed over, and only for a reason of their own
        val otherCourierResponse = ShippingApi.cancel(shippingUrl, TestUser.COURIER2, shipmentId, reason)
        val orderReasonResponse = ShippingApi.cancel(shippingUrl, TestUser.COURIER1, shipmentId, "OrderCancelled")
        assertProblem(otherCourierResponse, HttpURLConnection.HTTP_FORBIDDEN, "SHIPPING.NOT_ASSIGNED_COURIER")
        assertProblem(orderReasonResponse, HttpURLConnection.HTTP_BAD_REQUEST, "SHIPPING.INVALID_CANCELLATION_REASON")

        val cancelledShipment = ShippingApi.cancelExpectingSuccess(shippingUrl, TestUser.COURIER1, shipmentId, reason)
        assertThat(cancelledShipment.status).isEqualTo("Cancelled")
        assertThat(cancelledShipment.cancellationReason).isEqualTo(reason)
        assertThat(cancelledShipment.holder).isEqualTo("Courier")

        // the refund alone finalizes the customer's order, while the courier still has the goods
        val cancelled = "CANCELLED"
        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterRefund = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id)
            assertThat(orderAfterRefund.status).isEqualTo(cancelled)
            assertThat(orderAfterRefund.cancellationReason).isEqualTo("DELIVERY_FAILED")
            assertThat(orderAfterRefund.refund?.confirmedAt).isNotNull()
        }

        ShippingApi.confirmReturnedToInventory(shippingUrl, TestUser.COURIER1, shipmentId)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterReturn = InventoryApi.getProduct(inventoryUrl, product.id)
            assertThat(productAfterReturn.quantity).isEqualTo(product.quantity)
            assertThat(productAfterReturn.reserved).isEqualTo(product.reserved)
        }
        // the return is logistics: the customer's order stays as the refund left it
        assertThat(OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id).status).isEqualTo(cancelled)

        val refunds = PaymentApi.getRefundsByOrderId(paymentUrl, order.id)
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().status).isEqualTo("SUCCEEDED")
    }

    @Test
    fun `cancel shipment - recipient refusing the goods cancels the order by the customer`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val shippingUrl = DockerComposeStack.shippingServiceUrl
        val reason = "RecipientRefused"
        val product = E2eSharedSteps.createScenarioProduct(DockerComposeStack.inventoryServiceUrl, BigDecimal("79.99"))
        val order = orderInDelivery(product.id)
        val shipmentId = requireNotNull(order.shipment).id

        val cancelledShipment = ShippingApi.cancelExpectingSuccess(shippingUrl, TestUser.COURIER1, shipmentId, reason)
        assertThat(cancelledShipment.cancellationReason).isEqualTo(reason)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterRefund = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id)
            assertThat(orderAfterRefund.status).isEqualTo("CANCELLED")
            assertThat(orderAfterRefund.cancellationReason).isEqualTo("CANCELLED_BY_CUSTOMER")
            assertThat(orderAfterRefund.refund?.confirmedAt).isNotNull()
        }

        // the order is already cancelled - and refunded - so the customer has nothing left to cancel
        val response = OrderApi.requestOrderCancellation(orderUrl, TestUser.CLIENT1, order.id)
        assertProblem(response, HttpURLConnection.HTTP_CONFLICT, "ORDER.ORDER_NOT_CANCELLABLE")
    }

    @Test
    fun `cancel shipment - forbidden before pickup, when no courier has the goods yet`() {
        val shippingUrl = DockerComposeStack.shippingServiceUrl
        val product = E2eSharedSteps.createScenarioProduct(DockerComposeStack.inventoryServiceUrl, BigDecimal("79.99"))
        val order = E2eSharedSteps.placeOrderUntilShipmentCreated(
            DockerComposeStack.orderServiceUrl,
            DockerComposeStack.paymentServiceUrl,
            product.id,
            1,
        )
        val shipmentId = requireNotNull(order.shipment).id

        val response = ShippingApi.cancel(shippingUrl, TestUser.COURIER1, shipmentId, "RecipientUnavailable")

        assertProblem(response, HttpURLConnection.HTTP_FORBIDDEN, "SHIPPING.NOT_ASSIGNED_COURIER")
        assertThat(ShippingApi.getShipment(shippingUrl, TestUser.COURIER1, shipmentId).status).isEqualTo("Created")
    }
}
