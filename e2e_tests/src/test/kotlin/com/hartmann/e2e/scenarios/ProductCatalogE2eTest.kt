package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.common.assertProblem
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.seed.ProductSeeder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection

class ProductCatalogE2eTest : E2eScenarioTest() {

    @Test
    fun `getProducts - the catalog is public and paged in id order`() {
        val inventoryUrl = DockerComposeStack.inventoryServiceUrl
        val size = 2

        val firstPage = InventoryApi.getProducts(inventoryUrl, 0, size)
        val secondPage = InventoryApi.getProducts(inventoryUrl, 1, size)

        // the demo catalog alone has more than two pages; other scenarios may have added products since
        val ids = (firstPage.items + secondPage.items).map { it.id }
        assertThat(ids).hasSize(2 * size)
        assertThat(ids).isSorted()
        assertThat(ids).doesNotHaveDuplicates()
        assertThat(firstPage.size).isEqualTo(size)
        assertThat(firstPage.totalPages).isEqualTo((firstPage.totalElements + size - 1) / size)
    }

    @Test
    fun `getProducts - the demo catalog comes with a photo for every product`() {
        // the inventory-service's catalog seed: its products are the first in the catalog, each with a photo the web UI
        // serves; a product created through the API (ProductSeeder's) has none
        val firstProduct = InventoryApi.getProducts(DockerComposeStack.inventoryServiceUrl, 0, 1).items.single()

        assertThat(firstProduct.name).isEqualTo("Aluminium Laptop Stand")
        assertThat(firstProduct.imageUrl).isEqualTo("images/products/laptop-stand.webp")
    }

    @Test
    fun `getProducts - a product created through the API has no photo`() {
        val laptopId = ProductSeeder.laptopId
        val catalog = InventoryApi.getProducts(DockerComposeStack.inventoryServiceUrl, 0, MAX_PAGE_SIZE)

        val laptop = catalog.items.single { it.id == laptopId }

        assertThat(laptop.imageUrl).isNull()
    }

    @Test
    fun `getProducts - shows how many can be ordered, not the warehouse's stock counts`() {
        val item = InventoryApi.doGetProducts(DockerComposeStack.inventoryServiceUrl, 0, 1)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().jsonPath().getMap<String, Any>("items[0]")

        assertThat(item).containsKey("available")
        assertThat(item).doesNotContainKeys("quantity", "reserved")
    }

    @Test
    fun `getProducts - rejects a page size above the maximum`() {
        val response = InventoryApi.doGetProducts(DockerComposeStack.inventoryServiceUrl, 0, 101)

        assertProblem(response, HttpURLConnection.HTTP_BAD_REQUEST, "INVENTORY.INVALID_PAGE")
    }

    private companion object {
        // the inventory-service's largest page: the demo catalog and ProductSeeder's products fit in one
        const val MAX_PAGE_SIZE = 100
    }
}
