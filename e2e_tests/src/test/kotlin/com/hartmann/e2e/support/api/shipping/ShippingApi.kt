package com.hartmann.e2e.support.api.shipping

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.auth.givenAs
import io.restassured.response.ValidatableResponse
import java.net.HttpURLConnection
import java.util.UUID

object ShippingApi {
    /** A customer finds only their own shipment; a courier finds any. */
    fun findByTrackingNumber(baseUrl: String, user: TestUser, trackingNumber: String): ValidatableResponse =
        givenAs(user)
            .get("$baseUrl/api/v1/shipments/tracking/$trackingNumber")
            .then()

    fun getByTrackingNumber(baseUrl: String, user: TestUser, trackingNumber: String): ShipmentResponse =
        findByTrackingNumber(baseUrl, user, trackingNumber)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentResponse::class.java)

    /** Assigns the shipment to the courier; only they may deliver, lose or return it afterwards. */
    fun pickup(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "pickup")

    fun deliver(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "deliver")

    fun lose(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "lose")

    fun confirmReturnedToInventory(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "return")

    // admin-only until customers cancel through the order-service (docs/business_tasks.md, task 3)
    fun cancel(baseUrl: String, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, TestUser.ADMIN, shipmentId, "cancel")

    fun transition(baseUrl: String, user: TestUser, shipmentId: Long, action: String): ValidatableResponse =
        givenAs(user)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("$baseUrl/api/v1/shipments/$shipmentId/$action")
            .then()

    private fun transitionExpectingSuccess(baseUrl: String, user: TestUser, shipmentId: Long, action: String): ShipmentResponse =
        transition(baseUrl, user, shipmentId, action)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentResponse::class.java)
}
