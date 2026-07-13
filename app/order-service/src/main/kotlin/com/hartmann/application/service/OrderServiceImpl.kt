package com.hartmann.application.service

import com.hartmann.application.port.`in`.service.OrderService
import com.hartmann.application.port.out.kafka.OrderEventProducer
import com.hartmann.application.port.out.repository.OrderRepository
import com.hartmann.application.saga.event.OrderPlaced
import com.hartmann.domain.model.Order
import org.springframework.stereotype.Service

@Service
class OrderServiceImpl(
    private val orderRepository: OrderRepository,
    private val eventProducer: OrderEventProducer,
) : OrderService {
    override fun placeOrder(order: Order): Order {
        val storedOrder = orderRepository.save(order)
        eventProducer.publish(OrderPlaced(storedOrder.id))
        return storedOrder
    }

    override fun getOrder(orderId: Long): Order = orderRepository.getOrder(orderId)
}
