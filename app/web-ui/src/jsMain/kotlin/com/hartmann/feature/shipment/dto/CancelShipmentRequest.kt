package com.hartmann.feature.shipment.dto

import kotlinx.serialization.Serializable

@Serializable
data class CancelShipmentRequest(val reason: String)
