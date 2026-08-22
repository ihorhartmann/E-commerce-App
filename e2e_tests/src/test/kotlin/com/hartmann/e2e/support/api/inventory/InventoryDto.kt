package com.hartmann.e2e.support.api.inventory

import java.math.BigDecimal

data class InventoryMoneyDto(val amount: BigDecimal, val currency: String)

data class CreateProductRequest(
    val name: String,
    val description: String,
    val price: InventoryMoneyDto,
    val quantity: Int,
)

data class ProductResponse(
    val id: Long,
    val name: String,
    val description: String,
    val price: InventoryMoneyDto,
    val quantity: Int,
    val reserved: Int,
    val available: Int,
)
