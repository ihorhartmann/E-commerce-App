package com.hartmann.e2e.support.api.inventory

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.net.HttpURLConnection
import java.util.UUID

object InventoryApi {
    fun createProduct(baseUrl: String, request: CreateProductRequest): ProductResponse =
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(request)
            .post("$baseUrl/api/v1/products")
            .then()
            .statusCode(HttpURLConnection.HTTP_CREATED)
            .extract().`as`(ProductResponse::class.java)

    fun getProduct(baseUrl: String, productId: Long): ProductResponse =
        given()
            .get("$baseUrl/api/v1/products/$productId")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ProductResponse::class.java)
}
