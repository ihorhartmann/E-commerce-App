package com.hartmann.e2e.support.api.shipping

import io.restassured.RestAssured.given
import java.net.HttpURLConnection

object ShippingApi {
    fun getByTrackingNumber(baseUrl: String, trackingNumber: String): ShipmentResponse =
        given()
            .get("$baseUrl/api/v1/shipments/tracking/$trackingNumber")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentResponse::class.java)

    fun pickup(baseUrl: String, shipmentId: Long): ShipmentResponse = transition(baseUrl, shipmentId, "pickup")

    fun deliver(baseUrl: String, shipmentId: Long): ShipmentResponse = transition(baseUrl, shipmentId, "deliver")

    fun cancel(baseUrl: String, shipmentId: Long): ShipmentResponse = transition(baseUrl, shipmentId, "cancel")

    fun lose(baseUrl: String, shipmentId: Long): ShipmentResponse = transition(baseUrl, shipmentId, "lose")

    fun confirmReturnedToInventory(baseUrl: String, shipmentId: Long): ShipmentResponse =
        transition(baseUrl, shipmentId, "return")

    private fun transition(baseUrl: String, shipmentId: Long, action: String): ShipmentResponse =
        given()
            .post("$baseUrl/api/v1/shipments/$shipmentId/$action")
            .then()
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().`as`(ShipmentResponse::class.java)
}
