package com.hartmann.feature.catalog.dto

import com.hartmann.feature.catalog.model.ProductDetails
import com.hartmann.feature.catalog.model.ProductFields
import com.hartmann.core.network.dto.MoneyDto
import com.hartmann.core.network.dto.fromMoneyDto
import kotlinx.serialization.Serializable

@Serializable
data class ProductResponse(
    val id: Long,
    val name: String,
    val description: String,
    val price: MoneyDto,
    val quantity: Int,
    val reserved: Int,
)

fun fromProductResponse(response: ProductResponse): ProductDetails = ProductDetails(
    id = response.id,
    fields = ProductFields(response.name, response.description, fromMoneyDto(response.price), response.quantity),
    reserved = response.reserved,
)
