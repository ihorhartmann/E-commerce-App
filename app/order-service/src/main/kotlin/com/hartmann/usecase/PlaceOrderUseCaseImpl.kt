package com.hartmann.usecase

import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import com.hartmann.usecase.port.out.http.InventoryClient
import com.hartmann.usecase.port.out.http.PaymentClient
import com.hartmann.usecase.port.out.http.dto.ReservationItemDto
import com.hartmann.usecase.port.out.http.exception.InsufficientStockException
import com.hartmann.usecase.port.out.kafka.OrderEventProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.entities.model.Order
import org.springframework.stereotype.Service

@Service
class PlaceOrderUseCaseImpl(
    private val orderRepository: OrderRepository,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val eventProducer: OrderEventProducer,
) : PlaceOrderUseCase {

    override fun execute(order: Order): CheckoutResult {
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
}
