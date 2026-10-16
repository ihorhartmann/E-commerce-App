package com.hartmann.feature.catalog

import com.hartmann.feature.cart.CartStore
import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.catalog.model.CatalogProduct
import com.hartmann.feature.catalog.model.ProductDetails
import com.hartmann.feature.catalog.model.ProductFields
import com.hartmann.feature.catalog.model.ProductForm
import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.model.Money
import com.hartmann.core.notification.Notifier
import com.hartmann.core.model.Page
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// the ViewModel's fixed page size and the only currency inventory accepts - never what a test is about
private const val PAGE_SIZE = 12
private const val CURRENCY = "EUR"

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val catalogApi = mock<CatalogApi>()
    private val cartStore = mock<CartStore>(MockMode.autoUnit)
    private val notifier = mock<Notifier>(MockMode.autoUnit)
    private val errorHandler = mock<ErrorHandler>(MockMode.autoUnit)

    private fun TestScope.viewModel(cartItems: List<CartItem> = emptyList()): CatalogViewModel {
        every { cartStore.items } returns MutableStateFlow(cartItems)
        return CatalogViewModel(backgroundScope, catalogApi, cartStore, notifier, errorHandler)
    }

    @Test
    fun `load - shows the requested page of products`() = runTest {
        val pageNumber = 2
        val expected = Page(listOf(CatalogProduct(1, "Keyboard", "", Money(7900, CURRENCY), 12)), pageNumber, 3)
        everySuspend { catalogApi.listProducts(pageNumber, PAGE_SIZE) } returns expected
        val viewModel = viewModel()

        viewModel.load(pageNumber)
        runCurrent()

        assertEquals(expected, viewModel.state.value.page)
        assertFalse(viewModel.state.value.loading)
        verifySuspend(exactly(1)) { catalogApi.listProducts(pageNumber, PAGE_SIZE) }
    }

    @Test
    fun `load - hands a failure to the error handler and stops loading`() = runTest {
        val error = IllegalStateException("down")
        everySuspend { catalogApi.listProducts(any(), any()) } throws error
        val viewModel = viewModel()

        viewModel.load(0)
        runCurrent()

        assertFalse(viewModel.state.value.loading)
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `addToCart - puts the product into the cart and confirms it`() = runTest {
        val product = CatalogProduct(1, "Keyboard", "", Money(7900, CURRENCY), 12)
        every { cartStore.add(product) } returns true
        val viewModel = viewModel()

        viewModel.addToCart(product)

        verify(exactly(1)) { cartStore.add(product) }
        verify(exactly(1)) { notifier.success("Keyboard added to the cart.") }
        verify(not) { notifier.error(any()) }
    }

    @Test
    fun `addToCart - reports the product as sold out when the cart already holds all of its stock`() = runTest {
        val product = CatalogProduct(1, "Keyboard", "", Money(7900, CURRENCY), 12)
        every { cartStore.add(product) } returns false
        val viewModel = viewModel()

        viewModel.addToCart(product)

        verify(exactly(1)) { cartStore.add(product) }
        verify(exactly(1)) { notifier.error("No more Keyboard in stock.") }
        verify(not) { notifier.success(any()) }
    }

    @Test
    fun `openCreateDialog - opens an empty dialog for a new product`() = runTest {
        val viewModel = viewModel()

        viewModel.openCreateDialog()

        assertEquals(ProductDialogState(productId = null), viewModel.state.value.dialog)
    }

    @Test
    fun `openEditDialog - fills the dialog with the product's current values`() = runTest {
        val productId = 7L
        everySuspend { catalogApi.getProduct(productId) } returns
            ProductDetails(productId, ProductFields("Mouse", "Wireless", Money(2500, CURRENCY), 40), 3)
        val viewModel = viewModel()

        viewModel.openEditDialog(productId)
        runCurrent()

        val expected = ProductDialogState(productId, ProductForm("Mouse", "Wireless", "25.00", "40"))
        assertEquals(expected, viewModel.state.value.dialog)
        verifySuspend(exactly(1)) { catalogApi.getProduct(productId) }
    }

    @Test
    fun `openEditDialog - closes the dialog when the product can't be loaded`() = runTest {
        val error = IllegalStateException("not found")
        everySuspend { catalogApi.getProduct(any()) } throws error
        val viewModel = viewModel()

        viewModel.openEditDialog(7)
        runCurrent()

        assertNull(viewModel.state.value.dialog)
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `updateForm - takes the typed fields and clears the validation error`() = runTest {
        val viewModel = viewModel()
        viewModel.openCreateDialog()
        viewModel.saveProduct()
        val form = ProductForm(name = "Desk")

        viewModel.updateForm(form)

        assertEquals(ProductDialogState(productId = null, form = form), viewModel.state.value.dialog)
    }

    @Test
    fun `closeDialog - closes the dialog`() = runTest {
        val viewModel = viewModel()
        viewModel.openCreateDialog()

        viewModel.closeDialog()

        assertNull(viewModel.state.value.dialog)
    }

    @Test
    fun `saveProduct - creates a new product, then closes the dialog and reloads the page`() = runTest {
        val name = "Desk"
        val description = "Oak"
        val quantity = 4
        val fields = ProductFields(name, description, Money(15000, CURRENCY), quantity)
        val firstPage = 0
        everySuspend { catalogApi.createProduct(fields) } returns Unit
        everySuspend { catalogApi.listProducts(firstPage, PAGE_SIZE) } returns Page.empty()
        val viewModel = viewModel()
        viewModel.openCreateDialog()
        viewModel.updateForm(ProductForm(name, description, "150", quantity.toString()))

        viewModel.saveProduct()
        runCurrent()

        assertNull(viewModel.state.value.dialog)
        verifySuspend(exactly(1)) { catalogApi.createProduct(fields) }
        verifySuspend(not) { catalogApi.updateProduct(any(), any()) }
        verifySuspend(exactly(1)) { catalogApi.listProducts(firstPage, PAGE_SIZE) }
        verify(exactly(1)) { notifier.success("Desk created.") }
    }

    @Test
    fun `saveProduct - updates an existing product, then closes the dialog and reloads the page`() = runTest {
        val productId = 7L
        val fields = ProductFields("Mouse", "Wireless", Money(2500, CURRENCY), 40)
        val firstPage = 0
        everySuspend { catalogApi.getProduct(productId) } returns ProductDetails(productId, fields, 3)
        everySuspend { catalogApi.updateProduct(productId, fields) } returns Unit
        everySuspend { catalogApi.listProducts(firstPage, PAGE_SIZE) } returns Page.empty()
        val viewModel = viewModel()
        viewModel.openEditDialog(productId)
        runCurrent()

        viewModel.saveProduct()
        runCurrent()

        assertNull(viewModel.state.value.dialog)
        verifySuspend(exactly(1)) { catalogApi.updateProduct(productId, fields) }
        verifySuspend(not) { catalogApi.createProduct(any()) }
        verifySuspend(exactly(1)) { catalogApi.listProducts(firstPage, PAGE_SIZE) }
        verify(exactly(1)) { notifier.success("Mouse saved.") }
    }

    @Test
    fun `saveProduct - shows what's wrong with the form instead of calling the API`() = runTest {
        val viewModel = viewModel()
        viewModel.openCreateDialog()

        viewModel.saveProduct()
        runCurrent()

        assertEquals("Enter a name.", viewModel.state.value.dialog?.validationError)
        verifySuspend(not) { catalogApi.createProduct(any()) }
    }

    @Test
    fun `saveProduct - keeps the dialog open when saving fails`() = runTest {
        val error = IllegalStateException("rejected")
        everySuspend { catalogApi.createProduct(any()) } throws error
        val viewModel = viewModel()
        viewModel.openCreateDialog()
        viewModel.updateForm(ProductForm("Desk", "Oak", "150", "4"))

        viewModel.saveProduct()
        runCurrent()

        val dialog = assertNotNull(viewModel.state.value.dialog)
        assertFalse(dialog.saving)
        verify(exactly(1)) { errorHandler.handle(error) }
        verifySuspend(not) { catalogApi.listProducts(any(), any()) }
    }

    @Test
    fun `saveProduct - does nothing without an open dialog`() = runTest {
        val viewModel = viewModel()

        viewModel.saveProduct()
        runCurrent()

        verifySuspend(not) { catalogApi.createProduct(any()) }
        verifySuspend(not) { catalogApi.updateProduct(any(), any()) }
    }

    @Test
    fun `increaseQuantity - adds a piece while there's stock left`() = runTest {
        val productId = 1L
        val viewModel = viewModel(listOf(CartItem(productId, "Keyboard", Money(7900, CURRENCY), 2, 12)))

        viewModel.increaseQuantity(productId)

        verify(exactly(1)) { cartStore.setQuantity(productId, 3) }
    }

    @Test
    fun `increaseQuantity - does nothing once the cart holds all of the stock`() = runTest {
        val productId = 1L
        val stock = 12
        val viewModel = viewModel(listOf(CartItem(productId, "Keyboard", Money(7900, CURRENCY), stock, stock)))

        viewModel.increaseQuantity(productId)

        verify(not) { cartStore.setQuantity(any(), any()) }
    }

    @Test
    fun `increaseQuantity - does nothing for a product that isn't in the cart`() = runTest {
        val viewModel = viewModel()

        viewModel.increaseQuantity(1)

        verify(not) { cartStore.setQuantity(any(), any()) }
    }

    @Test
    fun `decreaseQuantity - takes a piece off`() = runTest {
        val productId = 1L
        val viewModel = viewModel(listOf(CartItem(productId, "Keyboard", Money(7900, CURRENCY), 2, 12)))

        viewModel.decreaseQuantity(productId)

        verify(exactly(1)) { cartStore.setQuantity(productId, 1) }
        verify(not) { cartStore.remove(any()) }
    }

    @Test
    fun `decreaseQuantity - takes the product out of the cart with its last piece`() = runTest {
        val productId = 1L
        val viewModel = viewModel(listOf(CartItem(productId, "Keyboard", Money(7900, CURRENCY), 1, 12)))
        viewModel.changeQuantity(productId, "abc")

        viewModel.decreaseQuantity(productId)

        verify(exactly(1)) { cartStore.remove(productId) }
        verify(not) { cartStore.setQuantity(any(), any()) }
        assertEquals(emptyMap(), viewModel.state.value.quantityInputs)
    }

    @Test
    fun `decreaseQuantity - does nothing for a product that isn't in the cart`() = runTest {
        val viewModel = viewModel()

        viewModel.decreaseQuantity(1)

        verify(not) { cartStore.setQuantity(any(), any()) }
        verify(not) { cartStore.remove(any()) }
    }

    @Test
    fun `changeQuantity - applies a valid quantity and drops what was typed before`() = runTest {
        val productId = 1L
        val viewModel = viewModel(listOf(CartItem(productId, "Keyboard", Money(7900, CURRENCY), 2, 12)))
        viewModel.changeQuantity(productId, "")

        viewModel.changeQuantity(productId, " 5 ")

        verify(exactly(1)) { cartStore.setQuantity(productId, 5) }
        assertEquals(emptyMap(), viewModel.state.value.quantityInputs)
    }

    @Test
    fun `changeQuantity - keeps an invalid quantity with the reason, leaving the cart as it is`() = runTest {
        val productId = 1L
        val stock = 12
        val text = "13"
        val viewModel = viewModel(listOf(CartItem(productId, "Keyboard", Money(7900, CURRENCY), 2, stock)))

        viewModel.changeQuantity(productId, text)

        val expected = mapOf(productId to QuantityInput(text, "Only $stock in stock."))
        assertEquals(expected, viewModel.state.value.quantityInputs)
        verify(not) { cartStore.setQuantity(any(), any()) }
    }

    @Test
    fun `changeQuantity - does nothing for a product that isn't in the cart`() = runTest {
        val viewModel = viewModel()

        viewModel.changeQuantity(1, "3")

        verify(not) { cartStore.setQuantity(any(), any()) }
        assertEquals(emptyMap(), viewModel.state.value.quantityInputs)
    }
}
