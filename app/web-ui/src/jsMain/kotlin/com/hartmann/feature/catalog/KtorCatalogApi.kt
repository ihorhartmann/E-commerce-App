package com.hartmann.feature.catalog

import com.hartmann.feature.catalog.dto.ListProductsResponse
import com.hartmann.feature.catalog.dto.ProductResponse
import com.hartmann.feature.catalog.dto.fromListProductsResponse
import com.hartmann.feature.catalog.dto.fromProductResponse
import com.hartmann.feature.catalog.dto.toCreateProductRequest
import com.hartmann.feature.catalog.dto.toUpdateProductRequest
import com.hartmann.feature.catalog.model.CatalogProduct
import com.hartmann.feature.catalog.model.ProductDetails
import com.hartmann.feature.catalog.model.ProductFields
import com.hartmann.core.network.bodyOrThrow
import com.hartmann.core.network.ensureSuccess
import com.hartmann.core.model.Page
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class KtorCatalogApi private constructor(private val client: HttpClient) : CatalogApi {
    override suspend fun listProducts(page: Int, size: Int): Page<CatalogProduct> =
        fromListProductsResponse(
            client.get(PRODUCTS_PATH) {
                parameter("page", page)
                parameter("size", size)
            }.bodyOrThrow<ListProductsResponse>()
        )

    override suspend fun getProduct(id: Long): ProductDetails =
        fromProductResponse(client.get("$PRODUCTS_PATH/$id").bodyOrThrow<ProductResponse>())

    override suspend fun createProduct(fields: ProductFields) {
        client.post(PRODUCTS_PATH) {
            contentType(ContentType.Application.Json)
            setBody(toCreateProductRequest(fields))
        }.ensureSuccess()
    }

    override suspend fun updateProduct(id: Long, fields: ProductFields) {
        client.put("$PRODUCTS_PATH/$id") {
            contentType(ContentType.Application.Json)
            setBody(toUpdateProductRequest(fields))
        }.ensureSuccess()
    }

    companion object {
        private const val PRODUCTS_PATH = "api/v1/products"

        fun create(client: HttpClient): CatalogApi = KtorCatalogApi(client)
    }
}
