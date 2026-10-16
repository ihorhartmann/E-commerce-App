package com.hartmann.feature.checkout.payment

import kotlin.js.Promise

// Stripe.js (https://js.stripe.com/v3/), loaded by index.html: Stripe requires it to be loaded from its own domain,
// never bundled. Only the calls this app makes are declared.

external fun Stripe(publishableKey: String): StripeJs

external interface StripeJs {
    fun elements(options: dynamic): StripeElements

    fun confirmPayment(options: dynamic): Promise<dynamic>
}

external interface StripeElements {
    fun create(type: String): StripeElement
}

external interface StripeElement {
    fun mount(selector: String)

    fun destroy()
}
