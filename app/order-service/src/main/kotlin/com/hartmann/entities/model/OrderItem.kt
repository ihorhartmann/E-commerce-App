package com.hartmann.entities.model

import com.hartmann.entities.value.Money

class OrderItem private constructor(
    val id: Long,
    val orderId: Long,
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val price: Money,
) {
    init {
        require(productId > 0) { "productId must be positive" }
        require(productName.isNotBlank()) { "productName must not be blank" }
        require(quantity > 0) { "quantity must be positive" }
    }

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
            return OrderItem(
                id = 0,
                orderId = 0,
                productId = productId,
                productName = productName,
                quantity = quantity,
                price = price,
            )
        }

        fun createExisting(
            id: Long,
            orderId: Long,
            productId: Long,
            productName: String,
            quantity: Int,
            price: Money,
        ): OrderItem {
            require(id > 0) { "id must be positive" }
            require(orderId > 0) { "orderId must be positive" }
            return OrderItem(
                id = id,
                orderId = orderId,
                productId = productId,
                productName = productName,
                quantity = quantity,
                price = price,
            )
        }
    }
}
