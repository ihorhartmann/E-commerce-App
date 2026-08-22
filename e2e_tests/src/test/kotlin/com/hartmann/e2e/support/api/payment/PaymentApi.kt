package com.hartmann.e2e.support.api.payment

import io.restassured.RestAssured.given
import java.net.HttpURLConnection

object PaymentApi {
    fun getPaymentByOrderId(baseUrl: String, orderId: Long): PaymentResponse =
        given()
            .get("$baseUrl/api/v1/payments/$orderId")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(PaymentResponse::class.java)

    fun getRefundsByOrderId(baseUrl: String, orderId: Long): List<RefundResponse> =
        given()
            .get("$baseUrl/api/v1/refunds/$orderId")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(Array<RefundResponse>::class.java).toList()
}
