package com.hartmann.feature.catalog.dto

import com.hartmann.feature.catalog.model.CatalogProduct
import com.hartmann.core.network.dto.MoneyDto
import com.hartmann.core.network.dto.fromMoneyDto
import com.hartmann.core.model.Page
import kotlinx.serialization.Serializable

@Serializable
data class ListProductsResponse(
    val items: List<CatalogProductResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

@Serializable
data class CatalogProductResponse(
    val id: Long,
    val name: String,
    val description: String,
    val price: MoneyDto,
    val available: Int,
)

fun fromListProductsResponse(response: ListProductsResponse): Page<CatalogProduct> =
    Page(response.items.map { fromCatalogProductResponse(it) }, response.page, response.totalPages)

private fun fromCatalogProductResponse(response: CatalogProductResponse): CatalogProduct = CatalogProduct(
    id = response.id,
    name = response.name,
    description = response.description,
    price = fromMoneyDto(response.price),
    available = response.available,
)
