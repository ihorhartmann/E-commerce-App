package com.hartmann.interfaces.kafka.dto

data class ShipmentStatusChangedEvent(val orderId: Long, val trackingNumber: String)
