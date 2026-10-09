package com.hartmann.usecase.port.out.repository.dto

import com.hartmann.entities.model.Order

data class OrderPage(val orders: List<Order>, val totalElements: Long)
