package com.hartmann.feature.cart

import com.hartmann.feature.cart.model.CartItem
import com.hartmann.feature.catalog.model.CatalogProduct
import kotlinx.coroutines.flow.StateFlow

/** The shopping cart, shared by the catalog (adds to it), the top bar (counts it) and the checkout (orders it). */
interface CartStore {
    val items: StateFlow<List<CartItem>>

    /** Adds one piece of [product]; false if the cart already holds all of its stock. */
    fun add(product: CatalogProduct): Boolean

    /** Sets the quantity of a product, kept between 1 and its stock. */
    fun setQuantity(productId: Long, quantity: Int)

    fun remove(productId: Long)

    fun clear()
}
