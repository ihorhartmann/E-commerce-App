package com.hartmann.usecase

import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.entities.model.Order
import org.springframework.stereotype.Service

@Service
class PlaceOrderUseCaseImpl(
    private val orderRepository: OrderRepository,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val kafkaProducer: OrderKafkaProducer,
) : PlaceOrderUseCase {

    override fun execute(order: Order): CheckoutResult {
        val createdOrder = orderRepository.save(order)

        try {
            inventoryClient.reserve(createdOrder.id, createdOrder.items)
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
            kafkaProducer.publishCancelReservation(createdOrder.id.toString(), createdOrder.id)
            throw e
        }

        return CheckoutResult(createdOrder, paymentIntent.clientSecret)
    }
}
