package com.hartmann.feature.cart.dto

import com.hartmann.feature.cart.model.CartItem
import com.hartmann.core.network.dto.MoneyDto
import com.hartmann.core.network.dto.fromMoneyDto
import com.hartmann.core.network.dto.toMoneyDto
import kotlinx.serialization.Serializable

/** A cart item as it is kept in sessionStorage. */
@Serializable
data class StoredCartItem(
    val productId: Long,
    val name: String,
    val price: MoneyDto,
    val quantity: Int,
    val available: Int,
)

fun fromStoredCartItem(stored: StoredCartItem): CartItem =
    CartItem(stored.productId, stored.name, fromMoneyDto(stored.price), stored.quantity, stored.available)

fun toStoredCartItem(item: CartItem): StoredCartItem =
    StoredCartItem(item.productId, item.name, toMoneyDto(item.price), item.quantity, item.available)
