package com.hartmann.usecase

import com.hartmann.entities.model.Order
import com.hartmann.usecase.port.`in`.usecase.CancelOrderUseCase
import com.hartmann.usecase.port.common.tracing.CorrelationTagger
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class CancelOrderUseCaseImpl(
    private val orderRepository: OrderRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
    private val paymentClient: PaymentClient,
    private val correlationTagger: CorrelationTagger,
    transactionManager: PlatformTransactionManager,
) : CancelOrderUseCase {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    // Not one transaction: an unpaid order is cancelled through payment-service, and that call must never sit inside a
    // database transaction.
    override fun execute(orderId: Long, customerId: UUID): Order {
        val correlationId = orderId.toString()
        // the order id is the saga's correlation id
        correlationTagger.tag(correlationId)

        val order = orderRepository.getOrder(orderId)

        // "not found" rather than "forbidden": another customer's order must not even be confirmed to exist
        if (!order.isOwnedBy(customerId)) {
            throw OrderNotFoundException(orderId)
        }

        if (order.isPaymentOpen()) {
            return cancelUnpaidOrder(orderId, correlationId)
        }
        return cancelPaidOrder(order, correlationId)
    }

    /**
     * Payment-service cancels the payment first, so it can no longer be paid; its PaymentCancelled then finishes the
     * cancellation. If it's already paid, the call fails, and the order moves on with the payment instead.
     */
    private fun cancelUnpaidOrder(orderId: Long, correlationId: String): Order {
        paymentClient.cancelPayment(correlationId, orderId)

        return requireNotNull(transactionTemplate.execute {
            // reloaded: the order loaded before the call to payment-service may have changed meanwhile - its
            // PaymentCancelled may even have cancelled it already
            val order = orderRepository.getOrder(orderId)
            // a repeated request, or the PaymentCancelled got here first: the order is already being cancelled, or is
            if (!order.requestCancellation()) {
                return@execute order
            }

            orderRepository.save(order)
        })
    }

    /** Shipping-service is asked to cancel the shipment; its answer then decides the refund and the order's end. */
    private fun cancelPaidOrder(order: Order, correlationId: String): Order = requireNotNull(transactionTemplate.execute {
        // a repeated request: the cancellation is already under way (shipping has already been asked), or done
        if (!order.requestCancellation()) {
            return@execute order
        }

        orderKafkaProducer.publishCancelShipment(correlationId, order.id)
        orderRepository.save(order)
    })
}
