package com.hartmann.feature.checkout

import com.hartmann.feature.cart.CartStore
import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.checkout.payment.PaymentGateway
import com.hartmann.feature.checkout.payment.PaymentResult
import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.error.launchSafely
import com.hartmann.core.model.Money
import com.hartmann.core.notification.Notifier
import com.hartmann.feature.order.OrderApi
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.AddressField
import com.hartmann.feature.order.model.OrderLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class CheckoutState(
    val address: Address = Address("", "", "", ""),
    val step: CheckoutStep = CheckoutStep.Editing,
    val busy: Boolean = false,
    /** Required address fields left blank on the last attempt to place the order, highlighted until filled in. */
    val invalidFields: Set<AddressField> = emptySet(),
    /** A problem not tied to one field, e.g. an empty cart. */
    val validationError: String? = null,
)

sealed interface CheckoutStep {
    /** Filling in the cart and the address. */
    data object Editing : CheckoutStep

    /** The order is placed and waits for its payment. */
    data class Paying(val orderId: Long, val clientSecret: String, val total: Money) : CheckoutStep

    /**
     * Done with this order: the screen moves on to the orders, with this one opened. [paid] tells whether the user
     * paid it here, so the orders screen watches for the payment-service to confirm it.
     */
    data class Finished(val orderId: Long, val paid: Boolean) : CheckoutStep
}

class CheckoutViewModel(
    private val scope: CoroutineScope,
    private val orderApi: OrderApi,
    private val cartStore: CartStore,
    private val paymentGateway: PaymentGateway,
    private val notifier: Notifier,
    private val errorHandler: ErrorHandler,
) {
    private val _state = MutableStateFlow(CheckoutState())
    val state: StateFlow<CheckoutState> = _state
    val cartItems: StateFlow<List<CartItem>> = cartStore.items

    fun updateAddress(address: Address) {
        // a highlighted field stops being highlighted as soon as it's filled in
        _state.update {
            it.copy(
                address = address,
                invalidFields = it.invalidFields intersect address.missingFields(),
                validationError = null,
            )
        }
    }

    fun changeQuantity(productId: Long, quantity: Int) = cartStore.setQuantity(productId, quantity)

    fun removeItem(productId: Long) = cartStore.remove(productId)

    fun placeOrder() {
        val items = cartStore.items.value
        val address = _state.value.address.trimmed()
        if (items.isEmpty()) {
            _state.update { it.copy(validationError = "Your cart is empty.") }
            return
        }
        val missingFields = address.missingFields()
        if (missingFields.isNotEmpty()) {
            _state.update { it.copy(invalidFields = missingFields) }
            return
        }
        _state.update { it.copy(busy = true) }
        scope.launchSafely(errorHandler) {
            try {
                val order = orderApi.placeOrder(items.map { OrderLine(it.productId, it.quantity) }, address)
                // the stock is reserved for the order now: the cart has done its job
                cartStore.clear()
                val step = if (order.clientSecret != null) {
                    CheckoutStep.Paying(order.orderId, order.clientSecret, order.total)
                } else {
                    notifier.error("Order #${order.orderId} was placed, but its payment couldn't be started.")
                    CheckoutStep.Finished(order.orderId, paid = false)
                }
                _state.update { it.copy(step = step) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun mountPaymentForm(containerSelector: String) {
        val step = _state.value.step as? CheckoutStep.Paying ?: return
        paymentGateway.mount(containerSelector, step.clientSecret)
    }

    fun unmountPaymentForm() = paymentGateway.unmount()

    fun pay() {
        val step = _state.value.step as? CheckoutStep.Paying ?: return
        _state.update { it.copy(busy = true) }
        scope.launchSafely(errorHandler) {
            try {
                when (val result = paymentGateway.confirm()) {
                    PaymentResult.Succeeded -> {
                        // Stripe took the payment; the order moves on once its webhook reaches the payment-service
                        notifier.success("Payment sent. You'll be notified once order #${step.orderId} is confirmed.")
                        _state.update { it.copy(step = CheckoutStep.Finished(step.orderId, paid = true)) }
                    }
                    // the form stays, so the user can fix the card or try another one
                    is PaymentResult.Failed -> notifier.error(result.message)
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Leaves an unpaid order as it is: the payment-service expires it, and its stock is released. */
    fun skipPayment() {
        val step = _state.value.step as? CheckoutStep.Paying ?: return
        notifier.success("Order #${step.orderId} wasn't paid - it will be cancelled automatically.")
        _state.update { it.copy(step = CheckoutStep.Finished(step.orderId, paid = false)) }
    }
}
