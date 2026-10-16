package com.hartmann.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hartmann.core.auth.model.CurrentUser
import com.hartmann.core.auth.model.Role
import com.hartmann.core.ui.Spinner
import com.hartmann.core.ui.Toasts
import com.hartmann.feature.catalog.CatalogMode
import com.hartmann.feature.catalog.CatalogScreen
import com.hartmann.feature.catalog.CatalogViewModel
import com.hartmann.feature.checkout.CheckoutScreen
import com.hartmann.feature.checkout.CheckoutViewModel
import com.hartmann.feature.order.OrdersScreen
import com.hartmann.feature.order.OrdersViewModel
import com.hartmann.feature.shipment.ShipmentsScreen
import com.hartmann.feature.shipment.ShipmentsViewModel
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private enum class Screen {
    CATALOG,
    CHECKOUT,
    ORDERS,
    SHIPMENTS,
}

/** Stripe sends a customer back here, to the orders, after a 3-D Secure check. */
private const val ORDERS_HASH = "#orders"

@Composable
fun App(dependencies: AppDependencies) {
    val scope = rememberCoroutineScope()
    val session = remember {
        SessionViewModel(scope, dependencies.sessionApi, dependencies.authFlowLauncher, dependencies.errorHandler)
    }
    LaunchedEffect(Unit) { session.load() }
    val sessionState by session.state.collectAsState()

    when (val state = sessionState) {
        SessionState.Loading -> Main({ classes("content") }) { Spinner() }
        SessionState.Guest -> Shell(dependencies, session, user = null)
        is SessionState.SignedIn -> Shell(dependencies, session, state.user)
    }
    Toasts(dependencies.notifier)
}

@Composable
private fun Shell(dependencies: AppDependencies, session: SessionViewModel, user: CurrentUser?) {
    val isShopper = user == null || user.hasRole(Role.CLIENT)
    val isCourier = user?.hasRole(Role.COURIER) == true
    var screen by remember { mutableStateOf(initialScreen(user)) }
    var focusOrderId by remember { mutableStateOf<Long?>(null) }
    var focusOrderPaid by remember { mutableStateOf(false) }
    val cartItems by dependencies.cartStore.items.collectAsState()

    Header({ classes("glass", "top-bar") }) {
        Span({ classes("brand") }) { Text("E-commerce") }
        Nav({ classes("nav") }) {
            NavButton("Catalog", screen == Screen.CATALOG) { screen = Screen.CATALOG }
            if (user?.hasRole(Role.CLIENT) == true) {
                NavButton("Your orders", screen == Screen.ORDERS) {
                    focusOrderId = null
                    screen = Screen.ORDERS
                }
            }
            if (isCourier) {
                NavButton("Shipments", screen == Screen.SHIPMENTS) { screen = Screen.SHIPMENTS }
            }
        }
        Div({ classes("top-bar-end") }) {
            if (isShopper) {
                NavButton("Cart (${cartItems.sumOf { it.quantity }})", screen == Screen.CHECKOUT) {
                    screen = Screen.CHECKOUT
                }
            }
            if (user == null) {
                NavButton("Sign in", active = false, onClick = session::signIn)
            } else {
                Span({ classes("user-name") }) { Text(user.displayName) }
                NavButton("Sign out", active = false, onClick = session::signOut)
            }
        }
    }

    Main({ classes("content") }) {
        when (screen) {
            Screen.CATALOG -> CatalogRoute(dependencies, catalogMode(user))
            Screen.CHECKOUT -> CheckoutRoute(
                dependencies,
                isGuest = user == null,
                onSignIn = session::signIn,
                onBrowse = { screen = Screen.CATALOG },
                onFinished = { orderId, paid ->
                    focusOrderId = orderId
                    focusOrderPaid = paid
                    screen = Screen.ORDERS
                },
            )
            Screen.ORDERS -> OrdersRoute(dependencies, focusOrderId, focusOrderPaid)
            Screen.SHIPMENTS -> ShipmentsRoute(dependencies)
        }
    }
}

// Each route builds its ViewModel on the route's own coroutine scope: leaving the screen cancels whatever it still
// runs, e.g. the polling of an order.

@Composable
private fun CatalogRoute(dependencies: AppDependencies, mode: CatalogMode) {
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        CatalogViewModel(scope, dependencies.catalogApi, dependencies.cartStore, dependencies.notifier, dependencies.errorHandler)
    }
    CatalogScreen(viewModel, mode)
}

@Composable
private fun CheckoutRoute(
    dependencies: AppDependencies,
    isGuest: Boolean,
    onSignIn: () -> Unit,
    onBrowse: () -> Unit,
    onFinished: (orderId: Long, paid: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        CheckoutViewModel(
            scope,
            dependencies.orderApi,
            dependencies.cartStore,
            dependencies.paymentGateway,
            dependencies.notifier,
            dependencies.errorHandler,
        )
    }
    CheckoutScreen(viewModel, isGuest, onSignIn, onBrowse, onFinished)
}

@Composable
private fun OrdersRoute(dependencies: AppDependencies, focusOrderId: Long?, focusOrderPaid: Boolean) {
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        OrdersViewModel(scope, dependencies.orderApi, dependencies.notifier, dependencies.errorHandler)
    }
    OrdersScreen(viewModel, focusOrderId, focusOrderPaid)
}

@Composable
private fun ShipmentsRoute(dependencies: AppDependencies) {
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        ShipmentsViewModel(scope, dependencies.shipmentApi, dependencies.notifier, dependencies.errorHandler)
    }
    ShipmentsScreen(viewModel)
}

@Composable
private fun NavButton(text: String, active: Boolean, onClick: () -> Unit) {
    Button({
        type(ButtonType.Button)
        classes("nav-button")
        if (active) {
            classes("nav-button-active")
            attr("aria-current", "page")
        }
        onClick { onClick() }
    }) { Text(text) }
}

private fun initialScreen(user: CurrentUser?): Screen {
    if (user?.hasRole(Role.CLIENT) == true && window.location.hash == ORDERS_HASH) {
        window.history.replaceState(null, "", window.location.pathname)
        return Screen.ORDERS
    }
    // a courier's work is the shipments; everyone else starts in the catalog
    if (user?.hasRole(Role.COURIER) == true && !user.hasRole(Role.CLIENT)) {
        return Screen.SHIPMENTS
    }
    return Screen.CATALOG
}

private fun catalogMode(user: CurrentUser?): CatalogMode = when {
    user == null || user.hasRole(Role.CLIENT) -> CatalogMode.SHOPPER
    user.hasRole(Role.ADMIN) -> CatalogMode.ADMIN
    else -> CatalogMode.VIEWER
}
