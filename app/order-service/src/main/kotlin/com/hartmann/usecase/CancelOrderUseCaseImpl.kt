package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.usecase.port.`in`.usecase.CancelOrderUseCase
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CancelOrderUseCaseImpl(
    private val orderRepository: OrderRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
) : CancelOrderUseCase {

    @Transactional
    override fun execute(orderId: Long, customerId: UUID): Order {
        var order = orderRepository.getOrder(orderId)
        // "not found" rather than "forbidden": another customer's order must not even be confirmed to exist
        if (!order.isOwnedBy(customerId)) {
            throw OrderNotFoundException(orderId)
        }
        // a repeated request: the cancellation is already under way, and shipping has already been asked
        if (!order.requestCancellation()) {
            return order
        }

        order = orderRepository.save(order)
        orderKafkaProducer.publishCancelShipment(order.id.toString(), order.id)
        return order
    }
}
