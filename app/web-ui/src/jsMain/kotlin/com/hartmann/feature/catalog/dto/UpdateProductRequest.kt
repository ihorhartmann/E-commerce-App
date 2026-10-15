package com.hartmann.feature.catalog.dto

import com.hartmann.feature.catalog.model.ProductFields
import com.hartmann.core.network.dto.MoneyDto
import com.hartmann.core.network.dto.toMoneyDto
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProductRequest(val name: String, val description: String, val price: MoneyDto, val quantity: Int)

fun toUpdateProductRequest(productFields: ProductFields): UpdateProductRequest =
    UpdateProductRequest(productFields.name, productFields.description, toMoneyDto(productFields.price), productFields.quantity)
