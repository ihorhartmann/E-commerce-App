package com.hartmann.feature.cart.model

import com.hartmann.core.model.Money

/**
 * A product in the cart. [available] is the stock when it was added - the cart doesn't let [quantity] go beyond it,
 * though inventory has the final say when the order is placed.
 */
data class CartItem(
    val productId: Long,
    val name: String,
    val price: Money,
    val quantity: Int,
    val available: Int,
) {
    fun subtotal(): Money = price * quantity

    fun canIncrease(): Boolean = quantity < available

    /** What's wrong with a quantity typed for this item, or null if it's a whole number from 1 up to [available]. */
    fun quantityError(text: String): String? {
        val quantity = text.trim().toIntOrNull()
        return when {
            quantity == null || quantity < 1 -> "Enter 1 or more."
            quantity > available -> "Only $available in stock."
            else -> null
        }
    }
}

/** The sum of [items], or null for an empty cart or one mixing currencies. */
fun cartTotal(items: List<CartItem>): Money? {
    if (items.isEmpty() || items.map { it.price.currency }.distinct().size > 1) {
        return null
    }
    return items.map { it.subtotal() }.reduce(Money::plus)
}
