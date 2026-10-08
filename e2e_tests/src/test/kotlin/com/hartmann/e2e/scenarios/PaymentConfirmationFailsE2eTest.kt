package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.auth.TestUser
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
    fun `place order - a declined card leaves the reservation held so retrying the same PaymentIntent with a valid card still completes the order`() {
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
            val payment = PaymentApi.getPaymentByOrderId(paymentUrl, placedOrder.id)
            assertThat(payment.status).isEqualTo("ATTEMPT_DECLINED")
        }

        // ATTEMPT_DECLINED is not terminal - the customer may still retry the same PaymentIntent
        // with a different payment method, so order-service is never told and the reservation
        // stays held rather than being released.
        val orderAfterDecline = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, placedOrder.id)
        assertThat(orderAfterDecline.status).isEqualTo("PLACED")
        val productAfterDecline = InventoryApi.getProduct(inventoryUrl, productId)
        assertThat(productAfterDecline.available).isEqualTo(product.available - quantity)
        assertThat(productAfterDecline.reserved).isEqualTo(product.reserved + quantity)

        // Retrying the same PaymentIntent (same clientSecret) with a valid card is exactly the
        // recovery path ATTEMPT_DECLINED exists to allow.
        StripeApi.confirmPaymentIntent(requireNotNull(placedOrder.clientSecret), StripeApi.TEST_CARD_SUCCESS)
            .statusCode(HttpURLConnection.HTTP_OK)

        await().atMost(AWAIT_TIMEOUT).untilAsserted {
            val orderAfterRetry = OrderApi.getOrder(orderUrl, TestUser.CLIENT1, placedOrder.id)
            assertThat(orderAfterRetry.status).isEqualTo("PROCESSING")
            assertThat(orderAfterRetry.paymentConfirmedAt).isNotNull()
        }
        val paymentAfterRetry = PaymentApi.getPaymentByOrderId(paymentUrl, placedOrder.id)
        assertThat(paymentAfterRetry.status).isEqualTo("CONFIRMED")
    }
}
