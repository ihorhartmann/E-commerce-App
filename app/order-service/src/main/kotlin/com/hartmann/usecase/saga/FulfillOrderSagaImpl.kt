package com.hartmann.usecase.saga

import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.out.idempotency.ProcessedKafkaMessageStore
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import com.hartmann.usecase.port.out.repository.OrderRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class FulfillOrderSagaImpl(
    private val orderRepository: OrderRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
    private val processedKafkaMessageStore: ProcessedKafkaMessageStore,
) : FulfillOrderSaga {

    @Transactional
    override fun onPaymentCompleted(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.confirmPayment()

            orderRepository.save(updatedOrder)
            orderKafkaProducer.publishCreateShipment(message.correlationId, updatedOrder.id, updatedOrder.address)
        }

    @Transactional
    override fun onPaymentFailed(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.failPayment()

            orderRepository.save(updatedOrder)
            orderKafkaProducer.publishCancelReservation(message.correlationId, updatedOrder.id)
        }

    @Transactional
    override fun onPaymentExpired(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.expirePayment()

            orderRepository.save(updatedOrder)
            orderKafkaProducer.publishCancelReservation(message.correlationId, updatedOrder.id)
        }

    @Transactional
    override fun onShipmentCreated(message: KafkaMessageMetadata, orderId: Long, trackingNumber: String) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.createShipment(trackingNumber)

            orderRepository.save(updatedOrder)
            orderKafkaProducer.publishHoldReservation(message.correlationId, updatedOrder.id)
        }

    @Transactional
    override fun onShipmentInDelivery(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.startDelivery()

            orderRepository.save(updatedOrder)
            orderKafkaProducer.publishFulfillReservation(message.correlationId, updatedOrder.id)
        }

    @Transactional
    override fun onShipmentDelivered(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            orderRepository.save(order.deliver())
        }

    @Transactional
    override fun onShipmentCancelled(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.cancelShipment()

            orderRepository.save(updatedOrder)
            if (updatedOrder.requiresReservationRelease()) {
                orderKafkaProducer.publishCancelReservation(message.correlationId, updatedOrder.id)
            }
            val refundAmount = updatedOrder.refundAmount()
            orderKafkaProducer.publishRefundRequested(message.correlationId, updatedOrder.id, refundAmount.amount, refundAmount.currency.name)
        }

    @Transactional
    override fun onShipmentLost(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.loseShipment()

            orderRepository.save(updatedOrder)
            val refundAmount = updatedOrder.refundAmount()
            orderKafkaProducer.publishRefundRequested(message.correlationId, updatedOrder.id, refundAmount.amount, refundAmount.currency.name)
        }

    @Transactional
    override fun onShipmentReturnedToInventory(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            val updatedOrder = order.returnToStock()
            orderRepository.save(updatedOrder)

            val items = updatedOrder.items.map { RestockItemDto(it.productId, it.quantity) }
            orderKafkaProducer.publishRestock(message.correlationId, updatedOrder.id, items)
        }

    @Transactional
    override fun onRefundCompleted(message: KafkaMessageMetadata, orderId: Long, amount: Money) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            orderRepository.save(order.confirmRefund(amount))
        }

    @Transactional
    override fun onRefundFailed(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            orderRepository.save(order.markRefundFailed())
        }

    /**
     * Marks message processed as the first statement of the caller's own @Transactional method,
     * so the dedup record and the business mutation commit or roll back together. If message was
     * already processed, action is skipped entirely.
     */
    private fun withIdempotency(message: KafkaMessageMetadata, action: () -> Unit) {
        val alreadyProcessed = processedKafkaMessageStore.markProcessed(message)
        if (alreadyProcessed) {
            return
        }

        action()
    }
}
