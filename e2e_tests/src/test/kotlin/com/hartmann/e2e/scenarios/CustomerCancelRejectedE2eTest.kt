package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.common.assertProblem
import com.hartmann.e2e.support.api.order.OrderApi
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

/** A customer may cancel only their own, paid order, and only while its shipment hasn't ended. */
class CustomerCancelRejectedE2eTest : E2eScenarioTest() {

    @Test
    fun `cancel order - rejected once the order is delivered, with nothing refunded`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl
        val shippingUrl = DockerComposeStack.shippingServiceUrl
        val product = E2eSharedSteps.createScenarioProduct(DockerComposeStack.inventoryServiceUrl, BigDecimal("79.99"))
        val order = E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, paymentUrl, product.id, 1)
        val shipmentId = requireNotNull(order.shipment).id
        ShippingApi.pickup(shippingUrl, TestUser.COURIER1, shipmentId)
        ShippingApi.deliver(shippingUrl, TestUser.COURIER1, shipmentId)
        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            assertThat(OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id).status).isEqualTo("FULFILLED")
        }

        val response = OrderApi.requestOrderCancellation(orderUrl, TestUser.CLIENT1, order.id)

        assertProblem(response, HttpURLConnection.HTTP_CONFLICT, "ORDER.ORDER_NOT_CANCELLABLE")
        val orderAfterRequest = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id)
        assertThat(orderAfterRequest.status).isEqualTo("FULFILLED")
        assertThat(orderAfterRequest.cancellationReason).isNull()
        assertThat(PaymentApi.getRefundsByOrderId(paymentUrl, order.id)).isEmpty()
    }

    @Test
    fun `cancel order - rejected for an order that is not paid yet`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val product = E2eSharedSteps.createScenarioProduct(DockerComposeStack.inventoryServiceUrl, BigDecimal("79.99"))
        val order = E2eSharedSteps.placeOrder(orderUrl, product.id, 1)

        val response = OrderApi.requestOrderCancellation(orderUrl, TestUser.CLIENT1, order.id)

        assertProblem(response, HttpURLConnection.HTTP_CONFLICT, "ORDER.ORDER_NOT_CANCELLABLE")
        assertThat(OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id).status).isEqualTo("PLACED")
    }

    @Test
    fun `cancel order - another customer's order is reported as not found and stays as it is`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val product = E2eSharedSteps.createScenarioProduct(DockerComposeStack.inventoryServiceUrl, BigDecimal("79.99"))
        val order = E2eSharedSteps.placeOrderUntilShipmentCreated(orderUrl, DockerComposeStack.paymentServiceUrl, product.id, 1)

        val otherCustomerResponse = OrderApi.requestOrderCancellation(orderUrl, TestUser.CLIENT2, order.id)
        val courierResponse = OrderApi.requestOrderCancellation(orderUrl, TestUser.COURIER1, order.id)

        // not 403: another customer's order must not even be confirmed to exist
        assertProblem(otherCustomerResponse, HttpURLConnection.HTTP_NOT_FOUND, "ORDER.ORDER_NOT_FOUND")
        assertProblem(courierResponse, HttpURLConnection.HTTP_FORBIDDEN, "ORDER.ACCESS_DENIED")
        assertThat(OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id).status).isEqualTo("PROCESSING")
    }
}
