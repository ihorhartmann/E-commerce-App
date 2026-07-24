package com.hartmann.usecase.port.out.kafka.dto

data class RestockItemDto(val productId: Long, val quantity: Int)
