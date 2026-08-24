package com.hartmann.e2e.support.api.payment

import com.hartmann.e2e.support.docker.DockerComposeStack
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse

object StripeApi {
    const val TEST_CARD_SUCCESS = "pm_card_visa"
    const val TEST_CARD_INSUFFICIENT_FUNDS = "pm_card_chargeDeclinedInsufficientFunds"

    /**
     * Confirms a PaymentIntent the way the real frontend does it: a plain HTTPS call authenticated
     * with the publishable key (never the secret key) plus the clientSecret, which is itself the
     * authorization proof for this one PaymentIntent - no Stripe SDK involved. StripeCliListener
     * forwards the resulting webhook (success or decline) to payment-service exactly like Stripe
     * would in production.
     */
    fun confirmPaymentIntent(clientSecret: String, paymentMethod: String): ValidatableResponse {
        val paymentIntentId = paymentIntentIdFromClientSecret(clientSecret)
        return given()
            .auth().preemptive().basic(DockerComposeStack.stripePublishableKey, "")
            .formParam("client_secret", clientSecret)
            .formParam("payment_method", paymentMethod)
            .post("https://api.stripe.com/v1/payment_intents/$paymentIntentId/confirm")
            .then()
    }

    /** A real Stripe clientSecret is "{paymentIntentId}_secret_{secret}". */
    private fun paymentIntentIdFromClientSecret(clientSecret: String): String = clientSecret.substringBefore("_secret_")
}
