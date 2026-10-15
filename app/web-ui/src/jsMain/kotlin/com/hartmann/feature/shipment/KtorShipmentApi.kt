package com.hartmann.feature.shipment

import com.hartmann.core.network.bodyOrThrow
import com.hartmann.core.model.Page
import com.hartmann.feature.shipment.dto.CancelShipmentRequest
import com.hartmann.feature.shipment.dto.ListShipmentsResponse
import com.hartmann.feature.shipment.dto.ShipmentResponse
import com.hartmann.feature.shipment.dto.fromListShipmentsResponse
import com.hartmann.feature.shipment.dto.fromShipmentResponse
import com.hartmann.feature.shipment.model.Shipment
import com.hartmann.feature.shipment.model.ShipmentCancellationReason
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class KtorShipmentApi private constructor(private val client: HttpClient) : ShipmentApi {
    override suspend fun listAvailable(page: Int, size: Int): Page<Shipment> = list("available", page, size)

    override suspend fun listMine(page: Int, size: Int): Page<Shipment> = list("mine", page, size)

    override suspend fun pickUp(id: Long): Shipment = transition(id, "pickup")

    override suspend fun deliver(id: Long): Shipment = transition(id, "deliver")

    override suspend fun reportLost(id: Long): Shipment = transition(id, "lose")

    override suspend fun cancel(id: Long, reason: ShipmentCancellationReason): Shipment =
        transition(id, "cancel") {
            contentType(ContentType.Application.Json)
            setBody(CancelShipmentRequest(reason.apiName))
        }

    override suspend fun confirmReturned(id: Long): Shipment = transition(id, "return")

    private suspend fun list(which: String, page: Int, size: Int): Page<Shipment> =
        fromListShipmentsResponse(
            client.get("$SHIPMENTS_PATH/$which") {
                parameter("page", page)
                parameter("size", size)
            }.bodyOrThrow<ListShipmentsResponse>()
        )

    private suspend fun transition(id: Long, action: String, block: HttpRequestBuilder.() -> Unit = {}): Shipment =
        fromShipmentResponse(client.post("$SHIPMENTS_PATH/$id/$action", block).bodyOrThrow<ShipmentResponse>())

    companion object {
        private const val SHIPMENTS_PATH = "api/v1/shipments"

        fun create(client: HttpClient): ShipmentApi = KtorShipmentApi(client)
    }
}
