package com.hartmann.e2e.support.api.order

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.net.HttpURLConnection
import java.util.UUID

object OrderApi {
    fun placeOrder(baseUrl: String, request: PlaceOrderRequest): ValidatableResponse =
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(request)
            .post("$baseUrl/api/v1/orders")
            .then()

    fun placeOrderExpectingSuccess(baseUrl: String, request: PlaceOrderRequest): OrderResponse =
        placeOrder(baseUrl, request)
            .statusCode(HttpURLConnection.HTTP_CREATED)
            .extract().`as`(OrderResponse::class.java)

    fun getOrder(baseUrl: String, orderId: Long): OrderResponse =
        given()
            .get("$baseUrl/api/v1/orders/$orderId")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(OrderResponse::class.java)
}
