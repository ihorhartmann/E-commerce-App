package com.hartmann.usecase

import com.hartmann.usecase.port.`in`.usecase.GetOrderUseCase
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import com.hartmann.entities.model.Order
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GetOrderUseCaseImpl(
    private val orderRepository: OrderRepository,
) : GetOrderUseCase {

    override fun execute(orderId: Long, customerId: UUID): Order {
        val order = orderRepository.getOrder(orderId)
        // "not found" rather than "forbidden": another customer's order must not even be confirmed to exist
        if (!order.isOwnedBy(customerId)) {
            throw OrderNotFoundException(orderId)
        }
        return order
    }
}
