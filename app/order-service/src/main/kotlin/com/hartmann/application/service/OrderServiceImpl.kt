package com.hartmann.application.service

import com.hartmann.application.port.`in`.service.OrderService
import com.hartmann.application.port.`in`.service.dto.CheckoutResult
import com.hartmann.application.port.out.http.InventoryClient
import com.hartmann.application.port.out.http.PaymentClient
import com.hartmann.application.port.out.http.dto.ReservationItemDto
import com.hartmann.application.port.out.http.exception.InsufficientStockException
import com.hartmann.application.port.out.kafka.OrderEventProducer
import com.hartmann.application.port.out.repository.OrderRepository
import com.hartmann.domain.model.Order
import org.springframework.stereotype.Service

@Service
class OrderServiceImpl(
    private val orderRepository: OrderRepository,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val eventProducer: OrderEventProducer,
) : OrderService {

    override fun placeOrder(order: Order): CheckoutResult {
        val createdOrder = orderRepository.save(order)
        val reservationItems = createdOrder.items.map { ReservationItemDto(it.productId, it.quantity.toInt()) }

        try {
            inventoryClient.reserve(createdOrder.id, reservationItems)
        } catch (e: InsufficientStockException) {
            orderRepository.save(createdOrder.outOfStock())
            throw e
        } catch (e: Exception) {
            orderRepository.save(createdOrder.failReservation())
            throw e
        }

        val paymentIntent = try {
            paymentClient.submitPayment(createdOrder.id, createdOrder.totalPrice())
        } catch (e: Exception) {
            orderRepository.save(createdOrder.failPayment())
            eventProducer.publishCancelReservation(createdOrder.id.toString(), createdOrder.id)
            throw e
        }

        return CheckoutResult(createdOrder, paymentIntent.clientSecret)
    }

    override fun getOrder(orderId: Long): Order = orderRepository.getOrder(orderId)
}
