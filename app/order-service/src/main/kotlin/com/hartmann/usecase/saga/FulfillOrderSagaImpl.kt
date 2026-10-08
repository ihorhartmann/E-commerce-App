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
            order.confirmPayment()

            orderRepository.save(order)
            orderKafkaProducer.publishCreateShipment(message.correlationId, order.id, order.customerId, order.address)
        }

    @Transactional
    override fun onPaymentFailed(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.failPayment()

            orderRepository.save(order)
            orderKafkaProducer.publishCancelReservation(message.correlationId, order.id)
        }

    @Transactional
    override fun onPaymentExpired(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.expirePayment()

            orderRepository.save(order)
            orderKafkaProducer.publishCancelReservation(message.correlationId, order.id)
        }

    @Transactional
    override fun onShipmentCreated(message: KafkaMessageMetadata, orderId: Long, trackingNumber: String) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.createShipment(trackingNumber)

            orderRepository.save(order)
            orderKafkaProducer.publishHoldReservation(message.correlationId, order.id)
        }

    @Transactional
    override fun onShipmentInDelivery(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.startDelivery()

            orderRepository.save(order)
            orderKafkaProducer.publishFulfillReservation(message.correlationId, order.id)
        }

    @Transactional
    override fun onShipmentDelivered(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.deliver()
            orderRepository.save(order)
        }

    @Transactional
    override fun onShipmentCancelled(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.cancelShipment()

            orderRepository.save(order)
            if (order.requiresReservationRelease()) {
                orderKafkaProducer.publishCancelReservation(message.correlationId, order.id)
            }
            val refundAmount = order.refundAmount()
            orderKafkaProducer.publishRefundRequested(message.correlationId, order.id, refundAmount.amount, refundAmount.currency.name)
        }

    @Transactional
    override fun onShipmentLost(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.loseShipment()

            orderRepository.save(order)
            val refundAmount = order.refundAmount()
            orderKafkaProducer.publishRefundRequested(message.correlationId, order.id, refundAmount.amount, refundAmount.currency.name)
        }

    @Transactional
    override fun onShipmentReturnedToInventory(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.returnToStock()
            orderRepository.save(order)

            val items = order.items.map { RestockItemDto(it.productId, it.quantity) }
            orderKafkaProducer.publishRestock(message.correlationId, order.id, items)
        }

    @Transactional
    override fun onRefundCompleted(message: KafkaMessageMetadata, orderId: Long, amount: Money) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.confirmRefund(amount)
            orderRepository.save(order)
        }

    @Transactional
    override fun onRefundFailed(message: KafkaMessageMetadata, orderId: Long) =
        withIdempotency(message) {
            val order = orderRepository.getOrder(orderId)
            order.markRefundFailed()
            orderRepository.save(order)
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
