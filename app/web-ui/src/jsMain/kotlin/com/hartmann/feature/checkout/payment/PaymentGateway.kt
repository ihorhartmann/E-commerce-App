package com.hartmann.feature.checkout.payment

/** The card form of the payment provider, drawn by the provider itself inside a container of the page. */
interface PaymentGateway {
    /** Shows the card form for the payment identified by [clientSecret] inside the element [containerSelector] matches. */
    fun mount(containerSelector: String, clientSecret: String)

    fun unmount()

    /** Confirms the payment with what the user typed into the mounted form. */
    suspend fun confirm(): PaymentResult
}

sealed interface PaymentResult {
    data object Succeeded : PaymentResult

    /** Declined or invalid card, etc.; [message] comes from the provider and is fit for the user. */
    data class Failed(val message: String) : PaymentResult
}
