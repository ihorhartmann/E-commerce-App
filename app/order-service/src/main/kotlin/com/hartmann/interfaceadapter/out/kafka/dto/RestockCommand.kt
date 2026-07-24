package com.hartmann.interfaceadapter.out.kafka.dto

data class RestockItem(val productId: Long, val quantity: Int)

data class RestockCommand(val orderId: Long, val items: List<RestockItem>)
