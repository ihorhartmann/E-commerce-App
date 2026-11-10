package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.payment.PaymentApi
import com.hartmann.e2e.support.api.payment.StripeApi
import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.HttpURLConnection

/**
 * A customer cancels an order they haven't paid yet: payment-service cancels its PaymentIntent at Stripe first, so it can
 * no longer be paid, then the payment; the order is then being cancelled, and payment-service's PaymentCancelled finishes
 * it and releases the stock.
 */
class CustomerCancelBeforePaymentE2eTest : E2eScenarioTest() {

    @Test
    fun `cancel order - before payment cancels the payment at Stripe, then the order, and releases the reservation`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl
        val product = E2eSharedSteps.createScenarioProduct(inventoryUrl, BigDecimal("79.99"))
        val order = E2eSharedSteps.placeOrder(orderUrl, product.id, 1)
        val clientSecret = requireNotNull(order.clientSecret)

        // being cancelled once the payment is - unless payment-service's PaymentCancelled got there first and finished it
        val orderAfterRequest = OrderApi.cancelOrder(orderUrl, TestUser.CLIENT1, order.id)
        assertThat(orderAfterRequest.status).isIn("CANCEL_REQUESTED", "CANCELLED")
        assertThat(orderAfterRequest.cancellationReason).isEqualTo("CANCELLED_BY_CUSTOMER")
        // asking again finds the payment already cancelled - and must not cancel or release anything twice
        OrderApi.cancelOrder(orderUrl, TestUser.CLIENT1, order.id)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterCancel = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, order.id)
            assertThat(orderAfterCancel.status).isEqualTo("CANCELLED")
            assertThat(orderAfterCancel.cancellationReason).isEqualTo("CANCELLED_BY_CUSTOMER")
            // nothing was charged, so nothing is refunded
            assertThat(orderAfterCancel.refund).isNull()
        }
        assertThat(PaymentApi.getPaymentByOrderId(paymentUrl, order.id).status).isEqualTo("CANCELLED")
        assertThat(PaymentApi.getRefundsByOrderId(paymentUrl, order.id)).isEmpty()

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterCancel = InventoryApi.getProduct(inventoryUrl, product.id)
            assertThat(productAfterCancel.reserved).isEqualTo(product.reserved)
            assertThat(productAfterCancel.available).isEqualTo(product.available)
        }

        // the PaymentIntent is cancelled at Stripe itself: the customer can no longer pay it
        StripeApi.confirmPaymentIntent(clientSecret, StripeApi.TEST_CARD_SUCCESS)
            .statusCode(HttpURLConnection.HTTP_BAD_REQUEST)
    }
}
