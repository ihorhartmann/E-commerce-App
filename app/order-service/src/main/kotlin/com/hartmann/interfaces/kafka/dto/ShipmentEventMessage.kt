package com.hartmann.interfaces.kafka.dto

data class ShipmentEventMessage(val orderId: Long, val shipmentId: Long, val trackingNumber: String)
