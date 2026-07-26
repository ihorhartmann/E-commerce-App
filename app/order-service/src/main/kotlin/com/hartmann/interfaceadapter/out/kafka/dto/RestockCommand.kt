package com.hartmann.interfaceadapter.out.kafka.dto

import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto

data class RestockItem(val productId: Long, val quantity: Int)

private fun toRestockItem(item: RestockItemDto): RestockItem = RestockItem(item.productId, item.quantity)

data class RestockCommand(val orderId: Long, val items: List<RestockItem>)

fun toRestockCommand(orderId: Long, items: List<RestockItemDto>): RestockCommand =
    RestockCommand(orderId, items.map { toRestockItem(it) })
