package com.hartmann.interfaces.kafka.dto

data class ShipmentEventMessage(val orderId: Long, val trackingNumber: String)
