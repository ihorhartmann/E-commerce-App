package com.hartmann.interfaceadapter.`in`.http.dto

import com.hartmann.entities.model.Order
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import java.time.Instant

data class ListOrdersResponse(
    val items: List<OrderSummaryResponseDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)

fun toListOrdersResponse(orderPage: OrderPage, page: Int, size: Int): ListOrdersResponse = ListOrdersResponse(
    items = orderPage.orders.map { toOrderSummaryResponseDto(it) },
    page = page,
    size = size,
    totalElements = orderPage.totalElements,
    totalPages = (orderPage.totalElements + size - 1) / size,
)

data class OrderSummaryResponseDto(
    val id: Long,
    val status: String,
    val totalPrice: MoneyResponseDto,
    val itemCount: Int,
    val createdAt: Instant,
)

private fun toOrderSummaryResponseDto(order: Order): OrderSummaryResponseDto = OrderSummaryResponseDto(
    id = order.id,
    status = order.status.name,
    totalPrice = toMoneyResponseDto(order.totalPrice()),
    itemCount = order.items.sumOf { it.quantity },
    createdAt = order.createdAt,
)
