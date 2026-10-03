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
        val order = orderRepository.save(order)

        try {
            inventoryClient.reserve(order.id, order.items)
        } catch (e: InsufficientStockException) {
            order.outOfStock()
            orderRepository.save(order)
            throw e
        } catch (e: Exception) {
            order.failReservation()
            orderRepository.save(order)
            throw e
        }

        val paymentIntent = try {
            paymentClient.submitPayment(order.id, order.totalPrice())
        } catch (e: Exception) {
            order.failPayment()
            orderRepository.save(order)
            kafkaProducer.publishCancelReservation(order.id.toString(), order.id)
            throw e
        }

        return CheckoutResult(order, paymentIntent.clientSecret)
    }
}
