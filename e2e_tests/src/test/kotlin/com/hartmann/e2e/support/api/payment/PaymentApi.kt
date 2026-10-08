package com.hartmann.e2e.support.api.payment

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.auth.givenAs
import java.net.HttpURLConnection

/** Payment-service reads are admin-only: customers see their payment and refund in the order instead. */
object PaymentApi {
    fun getPaymentByOrderId(baseUrl: String, orderId: Long): PaymentResponse =
        givenAs(TestUser.ADMIN)
            .get("$baseUrl/api/v1/payments/$orderId")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(PaymentResponse::class.java)

    fun getRefundsByOrderId(baseUrl: String, orderId: Long): List<RefundResponse> =
        givenAs(TestUser.ADMIN)
            .get("$baseUrl/api/v1/refunds/$orderId")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(Array<RefundResponse>::class.java).toList()
}
