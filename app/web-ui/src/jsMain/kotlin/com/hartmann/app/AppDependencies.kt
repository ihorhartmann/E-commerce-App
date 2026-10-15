package com.hartmann.app

import com.hartmann.core.auth.AuthFlowLauncher
import com.hartmann.core.auth.BrowserAuthFlowLauncher
import com.hartmann.core.auth.KtorSessionApi
import com.hartmann.core.auth.SessionApi
import com.hartmann.core.error.ErrorHandler
import com.hartmann.core.error.NotifyingErrorHandler
import com.hartmann.core.network.AppConfig
import com.hartmann.core.notification.Notifier
import com.hartmann.core.notification.ToastNotifier
import com.hartmann.feature.cart.CartStore
import com.hartmann.feature.cart.SessionStorageCartStore
import com.hartmann.feature.catalog.CatalogApi
import com.hartmann.feature.catalog.KtorCatalogApi
import com.hartmann.feature.checkout.payment.PaymentGateway
import com.hartmann.feature.checkout.payment.StripePaymentGateway
import com.hartmann.feature.order.KtorOrderApi
import com.hartmann.feature.order.OrderApi
import com.hartmann.feature.shipment.KtorShipmentApi
import com.hartmann.feature.shipment.ShipmentApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/** Everything that lives as long as the page; each screen builds its own ViewModel from these. */
class AppDependencies(httpClient: HttpClient, config: AppConfig, appScope: CoroutineScope) {
    val notifier: Notifier = ToastNotifier.create(appScope)
    val authFlowLauncher: AuthFlowLauncher = BrowserAuthFlowLauncher.create()
    val errorHandler: ErrorHandler = NotifyingErrorHandler.create(notifier, authFlowLauncher)
    val cartStore: CartStore = SessionStorageCartStore.create()
    val sessionApi: SessionApi = KtorSessionApi.create(httpClient)
    val catalogApi: CatalogApi = KtorCatalogApi.create(httpClient)
    val orderApi: OrderApi = KtorOrderApi.create(httpClient)
    val shipmentApi: ShipmentApi = KtorShipmentApi.create(httpClient)
    val paymentGateway: PaymentGateway = StripePaymentGateway.create(config.stripePublishableKey)
}
