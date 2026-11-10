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
import com.hartmann.feature.order.exception.OrderAlreadyPaidException
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.AddressField
import com.hartmann.feature.order.model.OrderLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

data class CheckoutState(
    val address: Address = Address("", "", "", ""),
    val step: CheckoutStep = CheckoutStep.Editing,
    val busy: Boolean = false,
    /** The unpaid order is being cancelled instead of paid. */
    val cancelling: Boolean = false,
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

/** One attempt to place an order, with the Idempotency-Key it is sent with every time the user tries it. */
private data class PlaceOrderAttempt(val lines: List<OrderLine>, val address: Address, val idempotencyKey: String)

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
    private var lastAttempt: PlaceOrderAttempt? = null

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
        val attempt = attemptFor(items.map { OrderLine(it.productId, it.quantity) }, address)
        _state.update { it.copy(busy = true) }
        scope.launchSafely(errorHandler) {
            try {
                val order = orderApi.placeOrder(attempt.lines, attempt.address, attempt.idempotencyKey)
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

    /**
     * The attempt to place [lines] for [address]: the last one again if it was for the same order, otherwise a new one
     * with a key of its own. A failed request may still have placed the order - the gateway gave up waiting, say, while
     * the order-service went on - so trying again must not place a second one: sent with the same key, it is answered
     * with the order already placed. A changed cart or address is another order, under another key: the services
     * reject a key sent again with a different request.
     */
    private fun attemptFor(lines: List<OrderLine>, address: Address): PlaceOrderAttempt {
        val attempt = lastAttempt?.takeIf { it.lines == lines && it.address == address }
            ?: PlaceOrderAttempt(lines, address, Uuid.generateV7().toString())
        lastAttempt = attempt
        return attempt
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

    /**
     * Cancels the unpaid order instead of paying it: its payment is cancelled first, so it can no longer be paid, and the
     * order follows moments later, releasing its stock. If it turns out to be paid already, it goes on as a paid order,
     * watched for its payment's confirmation like one paid here.
     */
    fun cancelOrder() {
        val step = _state.value.step as? CheckoutStep.Paying ?: return
        _state.update { it.copy(cancelling = true) }
        scope.launchSafely(errorHandler) {
            try {
                orderApi.cancelOrder(step.orderId)
                notifier.success("Cancelling order #${step.orderId}.")
                _state.update { it.copy(step = CheckoutStep.Finished(step.orderId, paid = false)) }
            } catch (alreadyPaid: OrderAlreadyPaidException) {
                notifier.warning(alreadyPaid.message)
                _state.update { it.copy(step = CheckoutStep.Finished(step.orderId, paid = true)) }
            } finally {
                _state.update { it.copy(cancelling = false) }
            }
        }
    }
}
