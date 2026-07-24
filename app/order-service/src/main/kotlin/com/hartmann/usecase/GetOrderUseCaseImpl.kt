package com.hartmann.usecase

import com.hartmann.usecase.port.`in`.usecase.GetOrderUseCase
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.entities.model.Order
import org.springframework.stereotype.Service

@Service
class GetOrderUseCaseImpl(
    private val orderRepository: OrderRepository,
) : GetOrderUseCase {

    override fun execute(orderId: Long): Order = orderRepository.getOrder(orderId)
}
