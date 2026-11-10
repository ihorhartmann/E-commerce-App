package com.hartmann.feature.checkout

import com.hartmann.feature.cart.CartStore
import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.checkout.payment.PaymentGateway
import com.hartmann.feature.checkout.payment.PaymentResult
import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.model.Money
import com.hartmann.core.notification.Notifier
import com.hartmann.feature.order.OrderApi
import com.hartmann.feature.order.exception.OrderAlreadyPaidException
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.AddressField
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderLine
import com.hartmann.feature.order.model.OrderStatus
import com.hartmann.feature.order.model.PlacedOrder
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.capture.capture
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verify.VerifyMode.Companion.not
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// filler values every order of these tests needs, never what a test is about
private const val CURRENCY = "EUR"
private val ADDRESS = Address("Kyiv", "01001", "Khreshchatyk St, 1")

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {
    private val orderApi = mock<OrderApi>()
    private val cartStore = mock<CartStore>(MockMode.autoUnit)
    private val paymentGateway = mock<PaymentGateway>(MockMode.autoUnit)
    private val notifier = mock<Notifier>(MockMode.autoUnit)
    private val errorHandler = mock<ErrorHandler>(MockMode.autoUnit)

    private fun TestScope.viewModel(items: List<CartItem>): CheckoutViewModel {
        every { cartStore.items } returns MutableStateFlow(items)
        return CheckoutViewModel(backgroundScope, orderApi, cartStore, paymentGateway, notifier, errorHandler)
    }

    /** A ViewModel whose order is placed and waits for its payment. */
    private fun TestScope.payingViewModel(orderId: Long, clientSecret: String, total: Money): CheckoutViewModel {
        everySuspend { orderApi.placeOrder(any(), any(), any()) } returns PlacedOrder(orderId, total, clientSecret)
        val viewModel = viewModel(listOf(CartItem(1, "Keyboard", total, 1, 12)))
        viewModel.updateAddress(ADDRESS)
        viewModel.placeOrder()
        runCurrent()
        return viewModel
    }

    @Test
    fun `updateAddress - takes the address and clears the validation error`() = runTest {
        val viewModel = viewModel(emptyList())
        viewModel.placeOrder()

        viewModel.updateAddress(ADDRESS)

        assertEquals(CheckoutState(address = ADDRESS), viewModel.state.value)
        assertNull(viewModel.state.value.validationError)
    }

    @Test
    fun `updateAddress - stops highlighting the fields that got filled in`() = runTest {
        val address = Address("Kyiv", "", "")
        val viewModel = viewModel(listOf(CartItem(1, "Keyboard", Money(7900, CURRENCY), 1, 12)))
        viewModel.placeOrder()

        viewModel.updateAddress(address)

        assertEquals(setOf(AddressField.POSTAL_CODE, AddressField.LINE1), viewModel.state.value.invalidFields)
        assertEquals(address, viewModel.state.value.address)
    }

    @Test
    fun `changeQuantity - sets the quantity in the cart`() = runTest {
        val productId = 1L
        val quantity = 3
        val viewModel = viewModel(emptyList())

        viewModel.changeQuantity(productId, quantity)

        verify(exactly(1)) { cartStore.setQuantity(productId, quantity) }
    }

    @Test
    fun `removeItem - removes the product from the cart`() = runTest {
        val productId = 1L
        val viewModel = viewModel(emptyList())

        viewModel.removeItem(productId)

        verify(exactly(1)) { cartStore.remove(productId) }
    }

    @Test
    fun `placeOrder - places the cart's order, empties the cart and asks for the payment`() = runTest {
        val productId = 1L
        val quantity = 2
        val orderId = 42L
        val clientSecret = "pi_secret"
        val total = Money(15800, CURRENCY)
        val address = Address(" Kyiv ", "01001", "Khreshchatyk St, 1")
        everySuspend { orderApi.placeOrder(any(), any(), any()) } returns PlacedOrder(orderId, total, clientSecret)
        val viewModel = viewModel(listOf(CartItem(productId, "Keyboard", Money(7900, CURRENCY), quantity, 12)))
        viewModel.updateAddress(address)

        viewModel.placeOrder()
        runCurrent()

        assertEquals(CheckoutStep.Paying(orderId, clientSecret, total), viewModel.state.value.step)
        assertFalse(viewModel.state.value.busy)
        verifySuspend(exactly(1)) { orderApi.placeOrder(listOf(OrderLine(productId, quantity)), address.trimmed(), any()) }
        verify(exactly(1)) { cartStore.clear() }
    }

    @Test
    fun `placeOrder - finishes with an error when the order's payment couldn't be started`() = runTest {
        val orderId = 42L
        val price = Money(7900, CURRENCY)
        everySuspend { orderApi.placeOrder(any(), any(), any()) } returns PlacedOrder(orderId, price, null)
        val viewModel = viewModel(listOf(CartItem(1, "Keyboard", price, 1, 12)))
        viewModel.updateAddress(ADDRESS)

        viewModel.placeOrder()
        runCurrent()

        assertEquals(CheckoutStep.Finished(orderId, paid = false), viewModel.state.value.step)
        verify(exactly(1)) { cartStore.clear() }
        verify(exactly(1)) { notifier.error("Order #$orderId was placed, but its payment couldn't be started.") }
    }

    @Test
    fun `placeOrder - refuses an empty cart without calling the API`() = runTest {
        val viewModel = viewModel(emptyList())
        viewModel.updateAddress(ADDRESS)

        viewModel.placeOrder()
        runCurrent()

        assertEquals("Your cart is empty.", viewModel.state.value.validationError)
        verifySuspend(not) { orderApi.placeOrder(any(), any(), any()) }
    }

    @Test
    fun `placeOrder - highlights the missing address fields without calling the API`() = runTest {
        val viewModel = viewModel(listOf(CartItem(1, "Keyboard", Money(7900, CURRENCY), 1, 12)))
        viewModel.updateAddress(Address("Kyiv", " ", ""))

        viewModel.placeOrder()
        runCurrent()

        assertEquals(setOf(AddressField.POSTAL_CODE, AddressField.LINE1), viewModel.state.value.invalidFields)
        verifySuspend(not) { orderApi.placeOrder(any(), any(), any()) }
    }

    @Test
    fun `placeOrder - keeps the cart when the order can't be placed`() = runTest {
        val error = IllegalStateException("insufficient stock")
        everySuspend { orderApi.placeOrder(any(), any(), any()) } throws error
        val viewModel = viewModel(listOf(CartItem(1, "Keyboard", Money(7900, CURRENCY), 1, 12)))
        viewModel.updateAddress(ADDRESS)

        viewModel.placeOrder()
        runCurrent()

        assertEquals(CheckoutStep.Editing, viewModel.state.value.step)
        assertFalse(viewModel.state.value.busy)
        verify(exactly(1)) { errorHandler.handle(error) }
        verify(not) { cartStore.clear() }
    }

    @Test
    fun `placeOrder - sends the same order again under the same Idempotency-Key`() = runTest {
        val keys = mutableListOf<String>()
        everySuspend { orderApi.placeOrder(any(), any(), capture(keys)) } throws IllegalStateException("gateway timeout")
        val viewModel = viewModel(listOf(CartItem(1, "Keyboard", Money(7900, CURRENCY), 1, 12)))
        viewModel.updateAddress(ADDRESS)

        viewModel.placeOrder()
        runCurrent()
        viewModel.placeOrder()
        runCurrent()

        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
        verifySuspend(exactly(2)) { orderApi.placeOrder(any(), any(), any()) }
    }

    @Test
    fun `placeOrder - sends an order with another address under a new Idempotency-Key`() = runTest {
        val keys = mutableListOf<String>()
        everySuspend { orderApi.placeOrder(any(), any(), capture(keys)) } throws IllegalStateException("gateway timeout")
        val viewModel = viewModel(listOf(CartItem(1, "Keyboard", Money(7900, CURRENCY), 1, 12)))
        viewModel.updateAddress(ADDRESS)
        viewModel.placeOrder()
        runCurrent()

        viewModel.updateAddress(ADDRESS.copy(line2 = "apt. 5"))
        viewModel.placeOrder()
        runCurrent()

        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
        verifySuspend(exactly(2)) { orderApi.placeOrder(any(), any(), any()) }
    }

    @Test
    fun `mountPaymentForm - shows the card form for the order's payment`() = runTest {
        val clientSecret = "pi_secret"
        val containerSelector = "#payment-form"
        val viewModel = payingViewModel(42, clientSecret, Money(7900, CURRENCY))

        viewModel.mountPaymentForm(containerSelector)

        verify(exactly(1)) { paymentGateway.mount(containerSelector, clientSecret) }
    }

    @Test
    fun `mountPaymentForm - does nothing before the order is placed`() = runTest {
        val viewModel = viewModel(emptyList())

        viewModel.mountPaymentForm("#payment-form")

        verify(not) { paymentGateway.mount(any(), any()) }
    }

    @Test
    fun `unmountPaymentForm - removes the card form`() = runTest {
        val viewModel = viewModel(emptyList())

        viewModel.unmountPaymentForm()

        verify(exactly(1)) { paymentGateway.unmount() }
    }

    @Test
    fun `pay - finishes the checkout once the payment succeeds`() = runTest {
        val orderId = 42L
        everySuspend { paymentGateway.confirm() } returns PaymentResult.Succeeded
        val viewModel = payingViewModel(orderId, "pi_secret", Money(7900, CURRENCY))

        viewModel.pay()
        runCurrent()

        assertEquals(CheckoutStep.Finished(orderId, paid = true), viewModel.state.value.step)
        assertFalse(viewModel.state.value.busy)
        verifySuspend(exactly(1)) { paymentGateway.confirm() }
        verify(exactly(1)) { notifier.success("Payment sent. You'll be notified once order #$orderId is confirmed.") }
    }

    @Test
    fun `pay - stays on the payment and tells why it failed`() = runTest {
        val orderId = 42L
        val clientSecret = "pi_secret"
        val message = "Your card was declined."
        val total = Money(7900, CURRENCY)
        everySuspend { paymentGateway.confirm() } returns PaymentResult.Failed(message)
        val viewModel = payingViewModel(orderId, clientSecret, total)

        viewModel.pay()
        runCurrent()

        assertEquals(CheckoutStep.Paying(orderId, clientSecret, total), viewModel.state.value.step)
        assertFalse(viewModel.state.value.busy)
        verify(exactly(1)) { notifier.error(message) }
    }

    @Test
    fun `pay - hands a failure to the error handler and lets the user try again`() = runTest {
        val error = IllegalStateException("Stripe.js not loaded")
        everySuspend { paymentGateway.confirm() } throws error
        val viewModel = payingViewModel(42, "pi_secret", Money(7900, CURRENCY))

        viewModel.pay()
        runCurrent()

        assertFalse(viewModel.state.value.busy)
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `pay - does nothing before the order is placed`() = runTest {
        val viewModel = viewModel(emptyList())

        viewModel.pay()
        runCurrent()

        verifySuspend(not) { paymentGateway.confirm() }
    }

    /** The order as the order-service answers the cancellation of an unpaid one: still as it was. */
    private fun unpaidOrder(orderId: Long, total: Money) = Order(
        id = orderId,
        status = OrderStatus.PLACED,
        cancellationReason = null,
        items = emptyList(),
        total = total,
        address = ADDRESS,
        createdAt = "2026-11-10T10:00:00Z",
        paymentConfirmedAt = null,
        shipment = null,
        refund = null,
    )

    @Test
    fun `cancelOrder - cancels the unpaid order and finishes the checkout`() = runTest {
        val orderId = 42L
        val total = Money(7900, CURRENCY)
        everySuspend { orderApi.cancelOrder(orderId) } returns unpaidOrder(orderId, total)
        val viewModel = payingViewModel(orderId, "pi_secret", total)

        viewModel.cancelOrder()
        runCurrent()

        assertEquals(CheckoutStep.Finished(orderId, paid = false), viewModel.state.value.step)
        assertFalse(viewModel.state.value.cancelling)
        verifySuspend(exactly(1)) { orderApi.cancelOrder(orderId) }
        verify(exactly(1)) { notifier.success("Cancelling order #$orderId.") }
        verifySuspend(not) { paymentGateway.confirm() }
    }

    @Test
    fun `cancelOrder - goes on with an order that turns out to be paid, warning that it can still be cancelled`() =
        runTest {
            val orderId = 42L
            val alreadyPaidEx = OrderAlreadyPaidException(orderId)
            everySuspend { orderApi.cancelOrder(orderId) } throws alreadyPaidEx
            val viewModel = payingViewModel(orderId, "pi_secret", Money(7900, CURRENCY))

            viewModel.cancelOrder()
            runCurrent()

            assertEquals(CheckoutStep.Finished(orderId, paid = true), viewModel.state.value.step)
            assertFalse(viewModel.state.value.cancelling)
            verify(exactly(1)) { notifier.warning(alreadyPaidEx.message) }
            verify(not) { errorHandler.handle(any()) }
        }

    @Test
    fun `cancelOrder - stays on the payment and hands a failure to the error handler`() = runTest {
        val orderId = 42L
        val clientSecret = "pi_secret"
        val total = Money(7900, CURRENCY)
        val error = IllegalStateException("payment is being processed")
        everySuspend { orderApi.cancelOrder(orderId) } throws error
        val viewModel = payingViewModel(orderId, clientSecret, total)

        viewModel.cancelOrder()
        runCurrent()

        assertEquals(CheckoutStep.Paying(orderId, clientSecret, total), viewModel.state.value.step)
        assertFalse(viewModel.state.value.cancelling)
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `cancelOrder - does nothing before the order is placed`() = runTest {
        val viewModel = viewModel(emptyList())

        viewModel.cancelOrder()
        runCurrent()

        assertEquals(CheckoutStep.Editing, viewModel.state.value.step)
        verifySuspend(not) { orderApi.cancelOrder(any()) }
    }
}
