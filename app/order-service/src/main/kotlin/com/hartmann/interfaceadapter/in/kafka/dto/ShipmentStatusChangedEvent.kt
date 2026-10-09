package com.hartmann.interfaceadapter.`in`.kafka.dto

data class ShipmentStatusChangedEvent(val orderId: Long, val shipmentId: Long)
