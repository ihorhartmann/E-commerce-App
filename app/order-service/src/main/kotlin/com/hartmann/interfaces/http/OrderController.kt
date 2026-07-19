package com.hartmann.interfaces.http

import com.hartmann.application.port.`in`.service.OrderService
import com.hartmann.interfaces.http.dto.OrderMapper
import com.hartmann.interfaces.http.dto.OrderResponse
import com.hartmann.interfaces.http.dto.PlaceOrderRequestDto
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
    private val orderService: OrderService,
    private val orderMapper: OrderMapper,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun placeOrder(@Valid @RequestBody request: PlaceOrderRequestDto): OrderResponse {
        val result = orderService.placeOrder(orderMapper.toDomainModel(request))
        return orderMapper.fromDomainModel(result.order, result.clientSecret)
    }

    @GetMapping("/{orderId}")
    fun getOrderById(@PathVariable orderId: Long): OrderResponse =
        orderMapper.fromDomainModel(orderService.getOrder(orderId))
}
