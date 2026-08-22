package com.hartmann.e2e.support.e2e

import com.hartmann.e2e.support.api.order.AddressRequest
import com.hartmann.e2e.support.api.order.MoneyRequest
import com.hartmann.e2e.support.api.order.OrderApi
import com.hartmann.e2e.support.api.order.OrderItemRequest
import com.hartmann.e2e.support.api.order.OrderResponse
import com.hartmann.e2e.support.api.payment.PaymentApi
import com.hartmann.e2e.support.api.payment.StripeApi
import com.hartmann.e2e.support.api.order.PlaceOrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.time.Duration

/** Steps shared by more than one scenario, so each scenario test reads as its own distinguishing steps only. */
object E2eSharedSteps {
    private val POLL_TIMEOUT: Duration = Duration.ofSeconds(90)

    fun placeOrder(
        orderBaseUrl: String,
        productId: Long,
        quantity: Int,
        unitPrice: BigDecimal,
        currency: String = "USD",
    ): OrderResponse =
        OrderApi.placeOrderExpectingSuccess(
            orderBaseUrl,
            PlaceOrderRequest(
                userId = 1,
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
    ): OrderResponse {
        val placedOrder = placeOrder(orderBaseUrl, productId, quantity, unitPrice)
        StripeApi.confirmPaymentIntent(requireNotNull(placedOrder.clientSecret), StripeApi.TEST_CARD_SUCCESS)
            .statusCode(HttpURLConnection.HTTP_OK)

        lateinit var processingOrder: OrderResponse
        await().atMost(POLL_TIMEOUT).untilAsserted {
            processingOrder = OrderApi.getOrder(orderBaseUrl, placedOrder.id)
            assertThat(processingOrder.status).isEqualTo("PROCESSING")
            assertThat(processingOrder.paymentConfirmedAt).isNotNull()
            assertThat(processingOrder.shipment).isNotNull()
        }

        val payment = PaymentApi.getPaymentByOrderId(paymentBaseUrl, placedOrder.id)
        assertThat(payment.status).isEqualTo("COMPLETED")
        assertThat(payment.amount).isEqualByComparingTo(processingOrder.totalPrice.amount)
        assertThat(payment.currency).isEqualTo(processingOrder.totalPrice.currency)

        return processingOrder
    }
}
