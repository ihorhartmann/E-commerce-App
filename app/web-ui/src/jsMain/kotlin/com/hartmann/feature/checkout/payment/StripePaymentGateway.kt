package com.hartmann.feature.checkout.payment

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.json

/**
 * Stripe's Payment Element: the card fields live in Stripe's own iframe, so card data never touches this app. The
 * PaymentIntent behind [mount]'s client secret was created by the payment-service when the order was placed.
 */
class StripePaymentGateway private constructor(private val publishableKey: String) : PaymentGateway {
    private val stripe: StripeJs by lazy { Stripe(publishableKey) }
    private var elements: StripeElements? = null
    private var paymentElement: StripeElement? = null

    override fun mount(containerSelector: String, clientSecret: String) {
        unmount()
        val elements = stripe.elements(json("clientSecret" to clientSecret, "appearance" to appearance()))
        val paymentElement = elements.create("payment")
        paymentElement.mount(containerSelector)
        this.elements = elements
        this.paymentElement = paymentElement
    }

    override fun unmount() {
        paymentElement?.destroy()
        paymentElement = null
        elements = null
    }

    override suspend fun confirm(): PaymentResult {
        val elements = elements ?: return PaymentResult.Failed("The payment form isn't ready yet.")
        val result = stripe.confirmPayment(
            json(
                "elements" to elements,
                // only cards needing 3-D Secure leave the page; they come back to the orders screen
                "redirect" to "if_required",
                "confirmParams" to json("return_url" to "${window.location.origin}/#orders"),
            )
        ).await()
        val error = result.error
        if (error == null || error == undefined) {
            return PaymentResult.Succeeded
        }
        return PaymentResult.Failed(error.message as? String ?: "The payment didn't go through.")
    }

    // matches the app's light glass style; Stripe draws its fields itself, so they can't take the app's CSS
    private fun appearance() = json(
        "theme" to "stripe",
        "variables" to json(
            "colorPrimary" to "#4f46e5",
            "colorBackground" to "#ffffff",
            "colorText" to "#1e1b4b",
            "borderRadius" to "10px",
            "fontFamily" to "Inter, system-ui, sans-serif",
        ),
    )

    companion object {
        fun create(publishableKey: String): PaymentGateway = StripePaymentGateway(publishableKey)
    }
}
