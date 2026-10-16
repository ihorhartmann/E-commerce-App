package com.hartmann.feature.order.dto

import com.hartmann.core.network.dto.MoneyDto
import com.hartmann.core.network.dto.fromMoneyDto
import com.hartmann.core.model.Page
import com.hartmann.feature.order.model.OrderStatus
import com.hartmann.feature.order.model.OrderSummary
import kotlinx.serialization.Serializable

@Serializable
data class ListOrdersResponse(
    val items: List<OrderSummaryResponseDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

@Serializable
data class OrderSummaryResponseDto(
    val id: Long,
    val status: OrderStatus,
    val totalPrice: MoneyDto,
    val itemCount: Int,
    val createdAt: String,
)

fun fromListOrdersResponse(response: ListOrdersResponse): Page<OrderSummary> = Page(
    response.items.map { OrderSummary(it.id, it.status, fromMoneyDto(it.totalPrice), it.itemCount, it.createdAt) },
    response.page,
    response.totalPages,
)
