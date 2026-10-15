package com.hartmann.feature.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.catalog.model.CatalogProduct
import com.hartmann.feature.catalog.model.ProductForm
import com.hartmann.core.ui.AppButton
import com.hartmann.core.ui.Badge
import com.hartmann.core.ui.BadgeTone
import com.hartmann.core.ui.ButtonStyle
import com.hartmann.core.ui.Dialog
import com.hartmann.core.ui.EmptyState
import com.hartmann.core.ui.Field
import com.hartmann.core.ui.FieldError
import com.hartmann.core.ui.FormError
import com.hartmann.core.ui.Pagination
import com.hartmann.core.ui.Spinner
import com.hartmann.core.ui.invalidWhen
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput

/** What the catalog lets its viewer do with a product. */
enum class CatalogMode {
    /** A customer or a guest: puts products into the cart. */
    SHOPPER,
    /** Creates and edits products. */
    ADMIN,
    /** Anyone else (a courier): only looks. */
    VIEWER,
}

private const val PRODUCT_PLACEHOLDER_IMAGE = "images/product-placeholder.svg"

@Composable
fun CatalogScreen(viewModel: CatalogViewModel, mode: CatalogMode) {
    val state by viewModel.state.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()
    LaunchedEffect(Unit) { viewModel.load(0) }

    Div({ classes("screen-header") }) {
        H1 { Text("Catalog") }
        if (mode == CatalogMode.ADMIN) {
            AppButton("New product", viewModel::openCreateDialog, ButtonStyle.PRIMARY)
        }
    }
    when {
        state.loading && state.page.items.isEmpty() -> Spinner()
        state.page.items.isEmpty() -> EmptyState("There are no products yet.")
        else -> Div({ classes("grid") }) {
            state.page.items.forEach { product ->
                val cartItem = cartItems.firstOrNull { it.productId == product.id }
                ProductCard(product, cartItem, state.quantityInputs[product.id], mode, viewModel)
            }
        }
    }
    Pagination(state.page, viewModel::load)
    state.dialog?.let { ProductDialog(it, viewModel) }
}

@Composable
private fun ProductCard(
    product: CatalogProduct,
    cartItem: CartItem?,
    quantityInput: QuantityInput?,
    mode: CatalogMode,
    viewModel: CatalogViewModel,
) {
    Div({ classes("glass", "card", "product-card") }) {
        Img(src = product.imageUrl ?: PRODUCT_PLACEHOLDER_IMAGE, alt = product.name) { classes("product-image") }
        H3({ classes("card-title") }) { Text(product.name) }
        P({ classes("muted", "product-description") }) { Text(product.description) }
        Div({ classes("row") }) {
            Span({ classes("price") }) { Text(product.price.format()) }
            if (product.isInStock()) {
                Badge("${product.available} in stock", BadgeTone.SUCCESS)
            } else {
                Badge("Out of stock", BadgeTone.DANGER)
            }
        }

        Div({ classes("actions") }) {
            when (mode) {
                CatalogMode.SHOPPER -> if (cartItem == null) {
                    AppButton("Add to cart", { viewModel.addToCart(product) }, ButtonStyle.PRIMARY, product.isInStock())
                } else {
                    QuantityStepper(
                        id = "quantity-${product.id}",
                        label = "Quantity of ${product.name} in the cart",
                        value = quantityInput?.text ?: cartItem.quantity.toString(),
                        error = quantityInput?.error,
                        canIncrease = cartItem.canIncrease(),
                        onDecrease = { viewModel.decreaseQuantity(product.id) },
                        onIncrease = { viewModel.increaseQuantity(product.id) },
                        onInput = { viewModel.changeQuantity(product.id, it) },
                    )
                }
                CatalogMode.ADMIN -> AppButton("Edit", { viewModel.openEditDialog(product.id) })
                CatalogMode.VIEWER -> {}
            }
        }
    }
}

@Composable
private fun ProductDialog(dialog: ProductDialogState, viewModel: CatalogViewModel) {
    Dialog(if (dialog.productId == null) "New product" else "Edit product", viewModel::closeDialog) {
        if (dialog.loading) {
            Spinner()
            return@Dialog
        }
        val form = dialog.form
        Field("product-name", "Name", form.name, { viewModel.updateForm(form.copy(name = it)) })
        Field(
            "product-description", "Description", form.description,
            { viewModel.updateForm(form.copy(description = it)) },
            multiline = true,
        )
        Div({ classes("field-row") }) {
            Field(
                "product-price", "Price (${ProductForm.CURRENCY})", form.price,
                { viewModel.updateForm(form.copy(price = it)) },
                placeholder = "19.99",
            )
            Field(
                "product-quantity", "Quantity in stock", form.quantity,
                { viewModel.updateForm(form.copy(quantity = it)) },
                placeholder = "10",
            )
        }
        FormError(dialog.validationError)
        Div({ classes("actions") }) {
            AppButton(if (dialog.saving) "Saving…" else "Save", viewModel::saveProduct, ButtonStyle.PRIMARY, !dialog.saving)
            AppButton("Cancel", viewModel::closeDialog)
        }
    }
}

/**
 * − quantity +, with the quantity editable by hand. [value] is what the field shows (the quantity, or what the user
 * typed while it isn't valid); [error] outlines it in red with the reason under it.
 */
@Composable
private fun QuantityStepper(
    id: String,
    label: String,
    value: String,
    error: String?,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onInput: (String) -> Unit,
) {
    Div({ classes("quantity") }) {
        Div({ classes("stepper") }) {
            AppButton("−", onDecrease)
            TextInput(value) {
                id(id)
                classes("input", "stepper-input")
                invalidWhen(error, id)
                attr("inputmode", "numeric")
                attr("aria-label", label)
                onInput { onInput(it.value) }
            }
            AppButton("+", onIncrease, enabled = canIncrease)
        }
        FieldError(error, id)
    }
}
