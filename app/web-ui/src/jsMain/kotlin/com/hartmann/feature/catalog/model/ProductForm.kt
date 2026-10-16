package com.hartmann.feature.catalog.model

import com.hartmann.core.model.Money

/** The product dialog's fields, as typed. Prices are in EUR only - inventory rejects any other currency. */
data class ProductForm(
    val name: String = "",
    val description: String = "",
    val price: String = "",
    val quantity: String = "",
) {
    /** What's wrong with the fields, or null if they make a valid [ProductFields]. */
    fun validationError(): String? = when {
        name.isBlank() -> "Enter a name."
        parsedPrice() == null -> "Enter a price above zero, e.g. 19.99."
        parsedQuantity() == null -> "Enter a whole quantity, 0 or more."
        else -> null
    }

    /** The fields to submit, or null if [validationError] reports a problem. */
    fun toProductFields(): ProductFields? {
        val price = parsedPrice() ?: return null
        val quantity = parsedQuantity() ?: return null
        if (name.isBlank()) {
            return null
        }
        return ProductFields(name.trim(), description.trim(), price, quantity)
    }

    private fun parsedPrice(): Money? = Money.parse(price, CURRENCY)?.takeIf { it.minorUnits > 0 }

    private fun parsedQuantity(): Int? = quantity.trim().toIntOrNull()?.takeIf { it >= 0 }

    companion object {
        const val CURRENCY = "EUR"

        fun from(fields: ProductFields): ProductForm = ProductForm(
            name = fields.name,
            description = fields.description,
            price = fields.price.toDecimalString(),
            quantity = fields.quantity.toString(),
        )
    }
}
