package com.hartmann.infrastructure.kafka.dto

data class RestockItemMessage(val productId: Long, val quantity: Int)

data class RestockMessage(val orderId: Long, val items: List<RestockItemMessage>)
