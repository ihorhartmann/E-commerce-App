package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.usecase.port.`in`.usecase.GetOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.FindCustomerOrdersUseCase
import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.interfaceadapter.`in`.http.dto.ListOrdersResponse
import com.hartmann.interfaceadapter.`in`.http.dto.OrderResponse
import com.hartmann.interfaceadapter.`in`.http.dto.PlaceOrderRequest
import com.hartmann.interfaceadapter.`in`.http.dto.fromPlaceOrderRequest
import com.hartmann.interfaceadapter.`in`.http.dto.toListOrdersResponse
import com.hartmann.interfaceadapter.`in`.http.dto.toOrderResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase,
    private val getOrderUseCase: GetOrderUseCase,
    private val findCustomerOrdersUseCase: FindCustomerOrdersUseCase,
) {
    private companion object {
        // the page size of a list request that doesn't ask for one
        const val DEFAULT_PAGE_SIZE = "20"
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun placeOrder(
        @Valid @RequestBody request: PlaceOrderRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): OrderResponse {
        val result = placeOrderUseCase.execute(fromPlaceOrderRequest(request, getCustomerId(jwt)))
        return toOrderResponse(result.order, result.clientSecret)
    }

    @GetMapping
    fun findCustomersOrders(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) size: Int,
        @AuthenticationPrincipal jwt: Jwt,
    ): ListOrdersResponse = toListOrdersResponse(findCustomerOrdersUseCase.execute(getCustomerId(jwt), page, size), page, size)

    @GetMapping("/{orderId}")
    fun getOrderById(@PathVariable orderId: Long, @AuthenticationPrincipal jwt: Jwt): OrderResponse =
        toOrderResponse(getOrderUseCase.execute(orderId, getCustomerId(jwt)))

    // the token's subject is the Keycloak user id - a UUID - and is the only trusted source of who the customer is
    private fun getCustomerId(jwt: Jwt): UUID = UUID.fromString(jwt.subject)
}
