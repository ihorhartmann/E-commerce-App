package com.hartmann.feature.catalog

import com.hartmann.feature.cart.CartStore
import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.catalog.model.CatalogProduct
import com.hartmann.feature.catalog.model.ProductForm
import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.error.launchSafely
import com.hartmann.core.notification.Notifier
import com.hartmann.core.model.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class CatalogState(
    val page: Page<CatalogProduct> = Page.empty(),
    val loading: Boolean = true,
    val dialog: ProductDialogState? = null,
    /** What the user typed into a product's quantity field while it isn't a valid quantity, by product id. */
    val quantityInputs: Map<Long, QuantityInput> = emptyMap(),
)

data class QuantityInput(val text: String, val error: String)

/** The admin's create/edit dialog; [productId] is null when creating. */
data class ProductDialogState(
    val productId: Long?,
    val form: ProductForm = ProductForm(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val validationError: String? = null,
)

class CatalogViewModel(
    private val scope: CoroutineScope,
    private val catalogApi: CatalogApi,
    private val cartStore: CartStore,
    private val notifier: Notifier,
    private val errorHandler: ErrorHandler,
) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state
    val cartItems: StateFlow<List<CartItem>> = cartStore.items

    fun load(page: Int) {
        _state.update { it.copy(loading = true) }
        scope.launchSafely(errorHandler) {
            try {
                val products = catalogApi.listProducts(page, PAGE_SIZE)
                _state.update { it.copy(page = products) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun addToCart(product: CatalogProduct) {
        if (cartStore.add(product)) {
            notifier.success("${product.name} added to the cart.")
        } else {
            notifier.error("No more ${product.name} in stock.")
        }
    }

    fun increaseQuantity(productId: Long) {
        val item = cartItem(productId) ?: return
        if (item.canIncrease()) {
            setQuantity(item, item.quantity + 1)
        }
    }

    /** Takes one piece off; the last one takes the product out of the cart. */
    fun decreaseQuantity(productId: Long) {
        val item = cartItem(productId) ?: return
        if (item.quantity > 1) {
            setQuantity(item, item.quantity - 1)
        } else {
            cartStore.remove(productId)
            clearQuantityInput(productId)
        }
    }

    /** Applies a quantity typed by hand, or keeps the text with the reason it can't be applied. */
    fun changeQuantity(productId: Long, text: String) {
        val item = cartItem(productId) ?: return
        val error = item.quantityError(text)
        if (error == null) {
            setQuantity(item, text.trim().toInt())
        } else {
            _state.update { it.copy(quantityInputs = it.quantityInputs + (productId to QuantityInput(text, error))) }
        }
    }

    fun openCreateDialog() {
        _state.update { it.copy(dialog = ProductDialogState(productId = null)) }
    }

    fun openEditDialog(productId: Long) {
        _state.update { it.copy(dialog = ProductDialogState(productId, loading = true)) }
        scope.launchSafely(errorHandler) {
            try {
                val product = catalogApi.getProduct(productId)
                updateDialog { it.copy(form = ProductForm.from(product.fields)) }
            } catch (error: Throwable) {
                closeDialog()
                throw error
            } finally {
                updateDialog { it.copy(loading = false) }
            }
        }
    }

    fun updateForm(form: ProductForm) {
        updateDialog { it.copy(form = form, validationError = null) }
    }

    fun closeDialog() {
        _state.update { it.copy(dialog = null) }
    }

    fun saveProduct() {
        val dialog = _state.value.dialog ?: return
        val fields = dialog.form.toProductFields()
        if (fields == null) {
            updateDialog { it.copy(validationError = dialog.form.validationError()) }
            return
        }
        updateDialog { it.copy(saving = true) }
        scope.launchSafely(errorHandler) {
            try {
                if (dialog.productId == null) {
                    catalogApi.createProduct(fields)
                    notifier.success("${fields.name} created.")
                } else {
                    catalogApi.updateProduct(dialog.productId, fields)
                    notifier.success("${fields.name} saved.")
                }
                closeDialog()
                load(_state.value.page.number)
            } finally {
                updateDialog { it.copy(saving = false) }
            }
        }
    }

    private fun cartItem(productId: Long): CartItem? = cartStore.items.value.firstOrNull { it.productId == productId }

    private fun setQuantity(item: CartItem, quantity: Int) {
        cartStore.setQuantity(item.productId, quantity)
        clearQuantityInput(item.productId)
    }

    private fun clearQuantityInput(productId: Long) {
        _state.update { it.copy(quantityInputs = it.quantityInputs - productId) }
    }

    private fun updateDialog(transform: (ProductDialogState) -> ProductDialogState) {
        _state.update { state -> state.copy(dialog = state.dialog?.let(transform)) }
    }

    private companion object {
        const val PAGE_SIZE = 12
    }
}
