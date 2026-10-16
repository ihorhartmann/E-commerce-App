package com.hartmann.feature.checkout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.cart.model.cartTotal
import com.hartmann.core.ui.AppButton
import com.hartmann.core.ui.ButtonStyle
import com.hartmann.core.ui.EmptyState
import com.hartmann.core.ui.Field
import com.hartmann.core.ui.FormError
import com.hartmann.feature.order.model.AddressField
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val PAYMENT_FORM_ID = "payment-form"

@Composable
fun CheckoutScreen(
    viewModel: CheckoutViewModel,
    isGuest: Boolean,
    onSignIn: () -> Unit,
    onBrowse: () -> Unit,
    onFinished: (orderId: Long, paid: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val items by viewModel.cartItems.collectAsState()

    when (val step = state.step) {
        CheckoutStep.Editing -> {
            Div({ classes("screen-header") }) { H1 { Text("Your cart") } }
            if (items.isEmpty()) {
                Div({ classes("glass", "panel") }) {
                    EmptyState("Your cart is empty.")
                    AppButton("Browse the catalog", onBrowse, ButtonStyle.PRIMARY)
                }
            } else {
                Div({ classes("columns") }) {
                    CartPanel(items, viewModel)
                    AddressPanel(state, isGuest, onSignIn, viewModel)
                }
            }
        }
        is CheckoutStep.Paying -> {
            Div({ classes("screen-header") }) { H1 { Text("Payment") } }
            PaymentPanel(step, state.busy, viewModel)
        }
        is CheckoutStep.Finished -> LaunchedEffect(step) { onFinished(step.orderId, step.paid) }
    }
}

@Composable
private fun CartPanel(items: List<CartItem>, viewModel: CheckoutViewModel) {
    Div({ classes("glass", "panel") }) {
        H2 { Text("Items") }
        items.forEach { item ->
            Div({ classes("cart-line") }) {
                Div({ classes("cart-line-name") }) {
                    Text(item.name)
                    Span({ classes("muted") }) { Text(" · ${item.price.format()}") }
                }
                Div({ classes("stepper") }) {
                    AppButton("−", { viewModel.changeQuantity(item.productId, item.quantity - 1) }, enabled = item.quantity > 1)
                    Span({ classes("stepper-value") }) { Text(item.quantity.toString()) }
                    AppButton("+", { viewModel.changeQuantity(item.productId, item.quantity + 1) }, enabled = item.quantity < item.available)
                }
                Span({ classes("cart-line-subtotal") }) { Text(item.subtotal().format()) }
                AppButton("Remove", { viewModel.removeItem(item.productId) })
            }
        }
        Div({ classes("cart-total") }) {
            Span { Text("Total") }
            Span { Text(cartTotal(items)?.format() ?: "—") }
        }
    }
}

@Composable
private fun AddressPanel(state: CheckoutState, isGuest: Boolean, onSignIn: () -> Unit, viewModel: CheckoutViewModel) {
    Div({ classes("glass", "panel") }) {
        H2 { Text("Delivery address") }
        if (isGuest) {
            P({ classes("muted") }) { Text("Sign in to place your order. Your cart stays as it is.") }
            AppButton("Sign in to check out", onSignIn, ButtonStyle.PRIMARY)
            return@Div
        }
        val address = state.address
        Div({ classes("field-row") }) {
            Field(
                "address-city", "City", address.city,
                { viewModel.updateAddress(address.copy(city = it)) }, "Kyiv",
                error = state.errorOf(AddressField.CITY),
            )
            Field(
                "address-postal-code", "Postal code", address.postalCode,
                { viewModel.updateAddress(address.copy(postalCode = it)) }, "01001",
                error = state.errorOf(AddressField.POSTAL_CODE),
            )
        }
        Field(
            "address-line1", "Address", address.line1,
            { viewModel.updateAddress(address.copy(line1 = it)) }, "Khreshchatyk St, 1",
            error = state.errorOf(AddressField.LINE1),
        )
        Field(
            "address-line2", "Apartment, floor (optional)", address.line2,
            { viewModel.updateAddress(address.copy(line2 = it)) },
        )
        FormError(state.validationError)
        AppButton(if (state.busy) "Placing order…" else "Place order", viewModel::placeOrder, ButtonStyle.PRIMARY, !state.busy)
    }
}

@Composable
private fun PaymentPanel(step: CheckoutStep.Paying, busy: Boolean, viewModel: CheckoutViewModel) {
    Div({ classes("glass", "panel", "payment-panel") }) {
        H2 { Text("Order #${step.orderId}") }
        P({ classes("muted") }) { Text("Your order is placed and its items are reserved. Pay to send it on its way.") }
        // Stripe draws its card fields into this element; Compose leaves it alone, as it has no children of its own
        Div({
            id(PAYMENT_FORM_ID)
            classes("stripe-container")
            ref {
                viewModel.mountPaymentForm("#$PAYMENT_FORM_ID")
                onDispose { viewModel.unmountPaymentForm() }
            }
        })
        Div({ classes("actions") }) {
            AppButton(if (busy) "Paying…" else "Pay ${step.total.format()}", viewModel::pay, ButtonStyle.PRIMARY, !busy)
            // the payment can't be resumed later: the order-service hands out the client secret only once
            AppButton("Leave unpaid", viewModel::skipPayment, enabled = !busy)
        }
    }
}

private fun CheckoutState.errorOf(field: AddressField): String? = field.missingMessage.takeIf { field in invalidFields }
