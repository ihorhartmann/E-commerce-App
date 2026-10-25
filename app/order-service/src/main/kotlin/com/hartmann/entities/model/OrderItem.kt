package com.hartmann.entities.model

import com.hartmann.entities.model.exception.InconsistentOrderException
import com.hartmann.entities.model.exception.OrderInvariantViolatedException
import com.hartmann.entities.value.Money

class OrderItem private constructor(
    val id: Long,
    val orderId: Long,
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val price: Money,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrderItem) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "OrderItem(id=$id, orderId=$orderId, productId=$productId, productName=$productName, quantity=$quantity, " +
                "price=$price)"

    companion object {
        /** [productName] and [price] are the product's as it was reserved - they no longer follow the catalog. */
        fun createNew(productId: Long, productName: String, quantity: Int, price: Money): OrderItem {
            findBrokenRule(productId, productName, quantity)?.let {
                throw OrderInvariantViolatedException(it, mapOf("productId" to productId))
            }
            return OrderItem(
                id = 0,
                orderId = 0,
                productId = productId,
                productName = productName,
                quantity = quantity,
                price = price,
            )
        }

        /** An item is stored as part of its order, so stored data that breaks its rules makes the order inconsistent. */
        fun createExisting(
            id: Long,
            orderId: Long,
            productId: Long,
            productName: String,
            quantity: Int,
            price: Money,
        ): OrderItem {
            val brokenRule = when {
                id <= 0 -> "item id must be positive"
                orderId <= 0 -> "item orderId must be positive"
                else -> findBrokenRule(productId, productName, quantity)
            }
            brokenRule?.let { throw InconsistentOrderException(orderId, it) }
            return OrderItem(
                id = id,
                orderId = orderId,
                productId = productId,
                productName = productName,
                quantity = quantity,
                price = price,
            )
        }

        /**
         * The first of the item's own rules its data breaks, or null. The same rules hold for a new and for a stored
         * item; only what breaking them means differs - a bug in [createNew], corrupted data in [createExisting].
         */
        private fun findBrokenRule(productId: Long, productName: String, quantity: Int): String? = when {
            productId <= 0 -> "item productId must be positive"
            productName.isBlank() -> "item productName must not be blank"
            quantity <= 0 -> "item quantity must be positive"
            else -> null
        }
    }
}
