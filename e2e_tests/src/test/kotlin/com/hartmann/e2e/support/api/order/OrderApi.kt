package com.hartmann.e2e.support.api.order

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.auth.givenAs
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.net.HttpURLConnection
import java.util.UUID

object OrderApi {
    fun placeOrder(baseUrl: String, customer: TestUser, request: PlaceOrderRequest): ValidatableResponse =
        givenAs(customer)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(request)
            .post("$baseUrl/api/v1/orders")
            .then()

    fun placeOrderExpectingSuccess(baseUrl: String, customer: TestUser, request: PlaceOrderRequest): OrderResponse =
        placeOrder(baseUrl, customer, request)
            .statusCode(HttpURLConnection.HTTP_CREATED)
            .extract().`as`(OrderResponse::class.java)

    fun findOrder(baseUrl: String, customer: TestUser, orderId: Long): ValidatableResponse =
        givenAs(customer)
            .get("$baseUrl/api/v1/orders/$orderId")
            .then()

    fun getOrder(baseUrl: String, customer: TestUser, orderId: Long): OrderResponse =
        findOrder(baseUrl, customer, orderId)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(OrderResponse::class.java)

    fun findCustomerOrders(baseUrl: String, customer: TestUser, page: Int, size: Int): ListOrdersResponse =
        givenAs(customer)
            .queryParam("page", page)
            .queryParam("size", size)
            .get("$baseUrl/api/v1/orders")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ListOrdersResponse::class.java)
}
