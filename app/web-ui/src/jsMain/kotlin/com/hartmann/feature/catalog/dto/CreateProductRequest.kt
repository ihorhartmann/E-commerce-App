package com.hartmann.feature.catalog.dto

import com.hartmann.feature.catalog.model.ProductFields
import com.hartmann.core.network.dto.MoneyDto
import com.hartmann.core.network.dto.toMoneyDto
import kotlinx.serialization.Serializable

@Serializable
data class CreateProductRequest(val name: String, val description: String, val price: MoneyDto, val quantity: Int)

fun toCreateProductRequest(productFields: ProductFields): CreateProductRequest =
    CreateProductRequest(productFields.name, productFields.description, toMoneyDto(productFields.price), productFields.quantity)
