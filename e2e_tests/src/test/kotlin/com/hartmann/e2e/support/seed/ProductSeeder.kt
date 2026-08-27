package com.hartmann.e2e.support.seed

import com.hartmann.e2e.support.api.inventory.CreateProductRequest
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.inventory.InventoryMoneyDto
import com.hartmann.e2e.support.docker.DockerComposeStack
import io.restassured.RestAssured.given
import java.math.BigDecimal
import java.net.HttpURLConnection

/**
 * Seeds the fixed product catalog every scenario relies on (Laptop/Keyboard/Mouse, expected to
 * land on ids 1/2/3 via insertion order into an empty table). start() is idempotent, same pattern
 * as DockerComposeStack: skips seeding if product 1 already exists, so repeated local runs
 * against a not-yet-wiped postgres-data volume don't fail on a duplicate insert.
 */
object ProductSeeder {
    private val seeded: Boolean by lazy {
        seedIfEmpty()
        true
    }

    fun start() {
        check(seeded)
    }

    private fun seedIfEmpty() {
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val alreadySeeded = given().get("$inventoryUrl/api/v1/products/1").statusCode() == HttpURLConnection.HTTP_OK
        if (alreadySeeded) return

        InventoryApi.createProduct(
            inventoryUrl,
            CreateProductRequest("Laptop", "Portable workstation", InventoryMoneyDto(BigDecimal("1299.99"), "USD"), 10),
        )
        InventoryApi.createProduct(
            inventoryUrl,
            CreateProductRequest("Keyboard", "Mechanical keyboard", InventoryMoneyDto(BigDecimal("149.99"), "USD"), 25),
        )
        InventoryApi.createProduct(
            inventoryUrl,
            CreateProductRequest("Mouse", "Wireless mouse", InventoryMoneyDto(BigDecimal("79.99"), "USD"), 40),
        )
    }
}
