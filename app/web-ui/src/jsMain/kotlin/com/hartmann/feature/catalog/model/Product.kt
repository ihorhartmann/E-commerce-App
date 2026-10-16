package com.hartmann.feature.catalog.model

import com.hartmann.core.model.Money

/** A product as the catalog lists it. [imageUrl] stays null until the backend stores product images. */
data class CatalogProduct(
    val id: Long,
    val name: String,
    val description: String,
    val price: Money,
    val available: Int,
    val imageUrl: String? = null,
) {
    fun isInStock(): Boolean = available > 0
}

/**
 * A product as an admin edits it: the [fields] they can change, plus what only the server sets - its [id] and the
 * [reserved] stock, held for orders.
 */
data class ProductDetails(val id: Long, val fields: ProductFields, val reserved: Int)

/** What an admin submits to create or update a product. */
data class ProductFields(val name: String, val description: String, val price: Money, val quantity: Int)
