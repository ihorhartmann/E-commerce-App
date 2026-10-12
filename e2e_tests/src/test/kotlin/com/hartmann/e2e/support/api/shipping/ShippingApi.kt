package com.hartmann.e2e.support.api.shipping

import com.hartmann.e2e.support.auth.TestUser
import com.hartmann.e2e.support.auth.givenAs
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.net.HttpURLConnection
import java.util.UUID

/** Couriers only: customers see their shipment in their order instead. */
object ShippingApi {
    /** Any shipment; its street only to the courier it's assigned to. */
    fun findShipment(baseUrl: String, user: TestUser, shipmentId: Long): ValidatableResponse =
        givenAs(user)
            .get("$baseUrl/api/v1/shipments/$shipmentId")
            .then()

    fun getShipment(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        findShipment(baseUrl, courier, shipmentId)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentResponse::class.java)

    /** Shipments no courier has picked up yet, oldest first, without their streets. */
    fun getAvailableShipments(baseUrl: String, courier: TestUser, page: Int, size: Int): ShipmentListResponse =
        getShipmentList(baseUrl, courier, "available", page, size)

    /** The courier's shipments still to deliver or bring back, oldest first. */
    fun getCourierActiveShipments(baseUrl: String, courier: TestUser, page: Int, size: Int): ShipmentListResponse =
        getShipmentList(baseUrl, courier, "mine", page, size)

    /** Assigns the shipment to the courier; only they may deliver, lose or return it afterwards. */
    fun pickup(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "pickup")

    fun deliver(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "deliver")

    fun lose(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "lose")

    fun confirmReturnedToInventory(baseUrl: String, courier: TestUser, shipmentId: Long): ShipmentResponse =
        transitionExpectingSuccess(baseUrl, courier, shipmentId, "return")

    /**
     * The assigned courier telling the goods couldn't be handed over, for a reason of theirs (`RecipientRefused`,
     * `RecipientUnavailable`); customers cancel their order through the order-service instead.
     */
    fun cancel(baseUrl: String, courier: TestUser, shipmentId: Long, reason: String): ValidatableResponse =
        givenAs(courier)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(CancelShipmentRequest(reason))
            .post("$baseUrl/api/v1/shipments/$shipmentId/cancel")
            .then()

    fun cancelExpectingSuccess(baseUrl: String, courier: TestUser, shipmentId: Long, reason: String): ShipmentResponse =
        cancel(baseUrl, courier, shipmentId, reason)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentResponse::class.java)

    fun transition(baseUrl: String, user: TestUser, shipmentId: Long, action: String): ValidatableResponse =
        givenAs(user)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("$baseUrl/api/v1/shipments/$shipmentId/$action")
            .then()

    private fun transitionExpectingSuccess(baseUrl: String, user: TestUser, shipmentId: Long, action: String): ShipmentResponse =
        transition(baseUrl, user, shipmentId, action)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentResponse::class.java)

    private fun getShipmentList(baseUrl: String, courier: TestUser, list: String, page: Int, size: Int): ShipmentListResponse =
        givenAs(courier)
            .queryParam("page", page)
            .queryParam("size", size)
            .get("$baseUrl/api/v1/shipments/$list")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentListResponse::class.java)
}
