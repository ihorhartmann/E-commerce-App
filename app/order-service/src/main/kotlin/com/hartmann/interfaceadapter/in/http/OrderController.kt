package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.usecase.port.`in`.usecase.GetOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.interfaceadapter.`in`.http.dto.OrderMapper
import com.hartmann.interfaceadapter.`in`.http.dto.OrderResponse
import com.hartmann.interfaceadapter.`in`.http.dto.PlaceOrderRequestDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase,
    private val getOrderUseCase: GetOrderUseCase,
    private val orderMapper: OrderMapper,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun placeOrder(@Valid @RequestBody request: PlaceOrderRequestDto): OrderResponse {
        val result = placeOrderUseCase.execute(orderMapper.toDomainModel(request))
        return orderMapper.fromDomainModel(result.order, result.clientSecret)
    }

    @GetMapping("/{orderId}")
    fun getOrderById(@PathVariable orderId: Long): OrderResponse =
        orderMapper.fromDomainModel(getOrderUseCase.execute(orderId))
}
