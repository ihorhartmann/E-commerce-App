package com.hartmann.e2e.support.seed

import com.hartmann.e2e.support.api.inventory.CreateProductRequest
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.inventory.InventoryMoneyDto
import com.hartmann.e2e.support.docker.DockerComposeStack
import java.math.BigDecimal

/**
 * Creates the products the scenarios share (Laptop/Keyboard/Mouse) and remembers their ids. The inventory-service
 * starts with its own demo catalog (its catalog seed), so these get whatever ids come after it: a scenario takes the id
 * from here, never assumes one. start() is idempotent, same pattern as DockerComposeStack: the products are created once
 * per run.
 */
object ProductSeeder {
    private val ids: Map<String, Long> by lazy { seed() }

    /** 10 available: a scenario that needs to ask for more than there is uses this one. */
    val laptopId: Long get() = ids.getValue(LAPTOP)

    val keyboardId: Long get() = ids.getValue(KEYBOARD)

    val mouseId: Long get() = ids.getValue(MOUSE)

    fun start() {
        check(ids.isNotEmpty())
    }

    private fun seed(): Map<String, Long> {
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        return listOf(
            CreateProductRequest(LAPTOP, "Portable workstation", InventoryMoneyDto(BigDecimal("1299.99"), "EUR"), 10),
            CreateProductRequest(KEYBOARD, "Mechanical keyboard", InventoryMoneyDto(BigDecimal("149.99"), "EUR"), 25),
            CreateProductRequest(MOUSE, "Wireless mouse", InventoryMoneyDto(BigDecimal("79.99"), "EUR"), 40),
        ).associate { request -> request.name to InventoryApi.createProduct(inventoryUrl, request).id }
    }

    private const val LAPTOP = "Laptop"
    private const val KEYBOARD = "Keyboard"
    private const val MOUSE = "Mouse"
}
