package com.hartmann.usecase.saga

import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.`in`.saga.event.PaymentCompletedEvent
import com.hartmann.usecase.port.`in`.saga.event.PaymentFailedEvent
import com.hartmann.usecase.port.`in`.saga.event.RefundCompletedEvent
import com.hartmann.usecase.port.`in`.saga.event.RefundFailedEvent
import com.hartmann.usecase.port.`in`.saga.event.ShipmentCreationFailedEvent
import com.hartmann.entities.model.Order
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class FulfillOrderSagaImpl(
    private val orderRepository: OrderRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
) : FulfillOrderSaga {

    @Transactional
    override fun onPaymentCompleted(event: PaymentCompletedEvent, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val updatedOrder = order.confirmPayment()

        orderRepository.save(updatedOrder)
        orderKafkaProducer.publishCreateShipment(correlationId, updatedOrder.id, updatedOrder.address)
    }

    @Transactional
    override fun onPaymentFailed(event: PaymentFailedEvent, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val updatedOrder = order.failPayment()

        orderRepository.save(updatedOrder)
        cancelReservations(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentCreationFailed(event: ShipmentCreationFailedEvent, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val updatedOrder = order.failShipmentCreation().requestRefund()

        orderRepository.save(updatedOrder)
        cancelReservations(updatedOrder, correlationId)
        publishRefundRequested(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentCreated(orderId: Long, trackingNumber: String, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.createShipment(trackingNumber)

        orderRepository.save(updatedOrder)
        orderKafkaProducer.publishHoldReservation(correlationId, updatedOrder.id)
    }

    @Transactional
    override fun onShipmentInDelivery(orderId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.startDelivery()

        orderRepository.save(updatedOrder)
        orderKafkaProducer.publishFulfillReservation(correlationId, updatedOrder.id)
    }

    @Transactional
    override fun onShipmentDelivered(orderId: Long) {
        val order = orderRepository.getOrder(orderId)
        orderRepository.save(order.deliver())
    }

    @Transactional
    override fun onShipmentCancelled(orderId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val wasPickedUp = order.wasPickedUp()
        val updatedOrder = order.cancelShipment().requestRefund()

        orderRepository.save(updatedOrder)
        if (!wasPickedUp) {
            cancelReservations(updatedOrder, correlationId)
        }
        publishRefundRequested(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentLost(orderId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.loseShipment().requestRefund()

        orderRepository.save(updatedOrder)
        publishRefundRequested(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentReturnedToInventory(orderId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.returnToStock()
        orderRepository.save(updatedOrder)

        val items = updatedOrder.items.map { RestockItemDto(it.productId, it.quantity) }
        orderKafkaProducer.publishRestock(correlationId, updatedOrder.id, items)
    }

    @Transactional
    override fun onRefundCompleted(event: RefundCompletedEvent, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val amount = Money(event.amount, Currency.fromCode(event.currency))

        orderRepository.save(order.confirmRefund(amount))
    }

    @Transactional
    override fun onRefundFailed(event: RefundFailedEvent, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        orderRepository.save(order.markRefundFailed())
    }

    private fun cancelReservations(order: Order, correlationId: String) {
        orderKafkaProducer.publishCancelReservation(correlationId, order.id)
    }

    private fun publishRefundRequested(order: Order, correlationId: String) {
        val total = order.totalPrice()
        orderKafkaProducer.publishRefundRequested(correlationId, order.id, total.amount, total.currency.name)
    }
}
