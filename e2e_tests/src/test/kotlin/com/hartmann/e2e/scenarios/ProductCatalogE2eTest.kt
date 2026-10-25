package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.api.common.assertProblem
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.docker.DockerComposeStack
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

        // the seeded Laptop/Keyboard/Mouse are ids 1/2/3; other scenarios may have added more since
        assertThat(firstPage.items.map { it.name }).containsExactly("Laptop", "Keyboard")
        assertThat(secondPage.items.first().name).isEqualTo("Mouse")
        assertThat(firstPage.size).isEqualTo(size)
        assertThat(firstPage.totalElements).isGreaterThanOrEqualTo(3)
        assertThat(firstPage.totalPages).isEqualTo((firstPage.totalElements + size - 1) / size)
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
}
