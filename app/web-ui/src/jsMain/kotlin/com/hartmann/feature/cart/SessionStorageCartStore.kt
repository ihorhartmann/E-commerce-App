package com.hartmann.feature.cart

import com.hartmann.feature.cart.dto.StoredCartItem
import com.hartmann.feature.cart.dto.fromStoredCartItem
import com.hartmann.feature.cart.dto.toStoredCartItem
import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.catalog.model.CatalogProduct
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Keeps the cart in sessionStorage: signing in reloads the whole page (the browser goes to Keycloak and back), and
 * the cart has to survive that. It's per tab and gone once the tab is closed.
 */
class SessionStorageCartStore private constructor() : CartStore {
    private val _items = MutableStateFlow(read())
    override val items: StateFlow<List<CartItem>> = _items

    override fun add(product: CatalogProduct): Boolean {
        val current = _items.value.firstOrNull { it.productId == product.id }
        if (current == null) {
            if (!product.isInStock()) {
                return false
            }
            save(_items.value + CartItem(product.id, product.name, product.price, 1, product.available))
            return true
        }
        if (current.quantity >= current.available) {
            return false
        }
        setQuantity(product.id, current.quantity + 1)
        return true
    }

    override fun setQuantity(productId: Long, quantity: Int) {
        save(_items.value.map { item ->
            if (item.productId == productId) item.copy(quantity = quantity.coerceIn(1, item.available)) else item
        })
    }

    override fun remove(productId: Long) {
        save(_items.value.filterNot { it.productId == productId })
    }

    override fun clear() {
        save(emptyList())
    }

    private fun save(items: List<CartItem>) {
        _items.value = items
        runCatching { window.sessionStorage.setItem(STORAGE_KEY, Json.encodeToString(items.map { toStoredCartItem(it) })) }
    }

    private fun read(): List<CartItem> = runCatching {
        window.sessionStorage.getItem(STORAGE_KEY)
            ?.let { Json.decodeFromString<List<StoredCartItem>>(it) }
            ?.map { fromStoredCartItem(it) }
    }.getOrNull() ?: emptyList()

    companion object {
        private const val STORAGE_KEY = "cart"

        fun create(): CartStore = SessionStorageCartStore()
    }
}
