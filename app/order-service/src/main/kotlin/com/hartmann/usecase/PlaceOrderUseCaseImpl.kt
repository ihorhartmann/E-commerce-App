package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.entities.value.Address
import com.hartmann.usecase.port.`in`.usecase.PlaceOrderUseCase
import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import com.hartmann.usecase.port.common.tracing.CorrelationTagger
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class PlaceOrderUseCaseImpl(
    private val orderRepository: OrderRepository,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val kafkaProducer: OrderKafkaProducer,
    private val correlationTagger: CorrelationTagger,
    transactionManager: PlatformTransactionManager,
) : PlaceOrderUseCase {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    // Deliberately not one transaction: the calls to the inventory and the payment service must never sit inside a
    // database transaction, and the order has to be committed before its payment is created - the payment service
    // reports back about it on its own from then on.
    override fun execute(customerId: UUID, requestedItems: List<RequestedItem>, address: Address): CheckoutResult {
        // The id is taken first, because the stock is reserved for it; the order itself is stored only once the
        // reservation succeeded, so a failed one leaves no order behind.
        val orderId = orderRepository.nextId()
        val correlationId = orderId.toString()
        // the order id is the saga's correlation id: tagged before anything can fail, so a failed checkout is found too
        correlationTagger.tag(correlationId)
        val reservedItems = inventoryClient.reserve(orderId, requestedItems)

        val order = try {
            orderRepository.create(Order.createNew(orderId, customerId, reservedItems, address))
        } catch (e: Exception) {
            // the stock is reserved, but there is no order to hold it for
            kafkaProducer.publishCancelReservation(correlationId, orderId)
            throw e
        }

        val paymentIntent = try {
            paymentClient.submitPayment(order.id, order.totalPrice())
        } catch (e: Exception) {
            order.failPayment()
            transactionTemplate.executeWithoutResult {
                orderRepository.save(order)
                kafkaProducer.publishCancelReservation(correlationId, order.id)
            }
            throw e
        }

        return CheckoutResult(order, paymentIntent.clientSecret)
    }
}
