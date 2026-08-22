package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.e2e.E2eSharedSteps
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.payment.PaymentApi
import com.hartmann.e2e.support.api.payment.StripeApi
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.HttpURLConnection

class PaymentConfirmationFailsE2eTest : E2eScenarioTest() {

    @Test
    fun `place order - PaymentIntent confirmation fails on a declined card, reservation released`() {
        val orderUrl = DockerComposeStack.orderServiceUrl
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val paymentUrl = DockerComposeStack.paymentServiceUrl

        val productId = 2L // seeded "Keyboard", 25 available
        val quantity = 1
        val product = InventoryApi.getProduct(inventoryUrl, productId)

        val placedOrder = E2eSharedSteps.placeOrder(orderUrl, productId, quantity, BigDecimal("149.99"))

        // Stripe declines the confirm call itself synchronously, then separately fires the async
        // payment_intent.payment_failed webhook that payment-service reacts to below
        StripeApi.confirmPaymentIntent(requireNotNull(placedOrder.clientSecret), StripeApi.TEST_CARD_INSUFFICIENT_FUNDS)
            .statusCode(HttpURLConnection.HTTP_PAYMENT_REQUIRED)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterFailure = OrderApi.getOrder(orderUrl, placedOrder.id)
            assertThat(orderAfterFailure.status).isEqualTo("CANCELLED")
            assertThat(orderAfterFailure.cancellationReason).isEqualTo("PAYMENT_FAILED")
        }

        // payment-service updates its own record to FAILED before publishing PaymentFailed, so by
        // the time the order above is CANCELLED this is already settled - a plain GET suffices
        val payment = PaymentApi.getPaymentByOrderId(paymentUrl, placedOrder.id)
        assertThat(payment.status).isEqualTo("FAILED")

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val productAfterFailure = InventoryApi.getProduct(inventoryUrl, productId)
            assertThat(productAfterFailure.available).isEqualTo(product.available)
            assertThat(productAfterFailure.reserved).isEqualTo(product.reserved)
        }
    }
}
