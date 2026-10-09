package com.hartmann.e2e.support.e2e

import com.hartmann.e2e.support.api.inventory.CreateProductRequest
import com.hartmann.e2e.support.api.inventory.InventoryApi
import com.hartmann.e2e.support.api.inventory.InventoryMoneyDto
import com.hartmann.e2e.support.api.inventory.ProductResponse
import com.hartmann.e2e.support.api.order.AddressRequest
import com.hartmann.e2e.support.api.order.MoneyRequest
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.order.OrderItemRequest
import com.hartmann.e2e.support.api.order.OrderResponse
import com.hartmann.e2e.support.api.payment.PaymentApi
import com.hartmann.e2e.support.api.payment.StripeApi
import com.hartmann.e2e.support.api.order.PlaceOrderRequest
import com.hartmann.e2e.support.auth.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.time.Duration

/** Steps shared by more than one scenario, so each scenario test reads as its own distinguishing steps only. */
object E2eSharedSteps {
    private val POLL_TIMEOUT: Duration = Duration.ofSeconds(90)

    /**
     * A product only this scenario orders, for a scenario that checks stock counts: the seeded products are shared,
     * and another scenario's reservation or fulfillment may still be landing on them over Kafka.
     */
    fun createScenarioProduct(inventoryBaseUrl: String, unitPrice: BigDecimal, quantity: Int = 10): ProductResponse =
        InventoryApi.createProduct(
            inventoryBaseUrl,
            CreateProductRequest("E2E scenario item", "ordered by one scenario only", InventoryMoneyDto(unitPrice, "USD"), quantity),
        )

    fun placeOrder(
        orderBaseUrl: String,
        productId: Long,
        quantity: Int,
        unitPrice: BigDecimal,
        currency: String = "USD",
        customer: TestUser = TestUser.CLIENT1,
    ): OrderResponse =
        OrderApi.placeOrderExpectingSuccess(
            orderBaseUrl,
            customer,
            PlaceOrderRequest(
                items = listOf(OrderItemRequest(productId, quantity, MoneyRequest(unitPrice, currency))),
                address = AddressRequest(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1"),
            ),
        )

    fun placeOrderUntilShipmentCreated(
        orderBaseUrl: String,
        paymentBaseUrl: String,
        productId: Long,
        quantity: Int,
        unitPrice: BigDecimal,
        customer: TestUser = TestUser.CLIENT1,
    ): OrderResponse {
        val placedOrder = placeOrder(orderBaseUrl, productId, quantity, unitPrice, customer = customer)
        // Stripe amounts are minor units (cents) - confirms the exact amount it received matches
        // what we sent, not just that it deserialized back into the same value in our own DB.
        val expectedCents = placedOrder.totalPrice.amount.movePointRight(2).toInt()
        val actualCents = StripeApi.confirmPaymentIntent(requireNotNull(placedOrder.clientSecret), StripeApi.TEST_CARD_SUCCESS)
            .statusCode(HttpURLConnection.HTTP_OK)
            .extract().path<Int>("amount")
        assertThat(actualCents).isEqualTo(expectedCents)

        lateinit var processingOrder: OrderResponse
        await().atMost(POLL_TIMEOUT).untilAsserted {
            processingOrder = OrderApi.getOrder(orderBaseUrl, customer, placedOrder.id)
            assertThat(processingOrder.status).isEqualTo("PROCESSING")
            assertThat(processingOrder.paymentConfirmedAt).isNotNull()
            assertThat(processingOrder.shipment).isNotNull()
        }

        val payment = PaymentApi.getPaymentByOrderId(paymentBaseUrl, placedOrder.id)
        assertThat(payment.status).isEqualTo("CONFIRMED")
        assertThat(payment.amount).isEqualByComparingTo(processingOrder.totalPrice.amount)
        assertThat(payment.currency).isEqualTo(processingOrder.totalPrice.currency)

        return processingOrder
    }
}
