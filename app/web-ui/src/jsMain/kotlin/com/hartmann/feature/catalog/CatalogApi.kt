package com.hartmann.feature.catalog

import com.hartmann.feature.catalog.model.CatalogProduct
import com.hartmann.feature.catalog.model.ProductDetails
import com.hartmann.feature.catalog.model.ProductFields
import com.hartmann.core.model.Page

interface CatalogApi {
    suspend fun listProducts(page: Int, size: Int): Page<CatalogProduct>

    suspend fun getProduct(id: Long): ProductDetails

    suspend fun createProduct(fields: ProductFields)

    suspend fun updateProduct(id: Long, fields: ProductFields)
}
