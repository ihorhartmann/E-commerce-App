package com.hartmann.application.saga

import com.hartmann.application.port.`in`.saga.FulfillOrderSaga
import com.hartmann.application.port.out.kafka.OrderEventProducer
import com.hartmann.application.port.out.kafka.dto.RestockItemDto
import com.hartmann.application.port.out.repository.OrderRepository
import com.hartmann.application.saga.event.PaymentCompleted
import com.hartmann.application.saga.event.PaymentFailed
import com.hartmann.application.saga.event.RefundCompleted
import com.hartmann.application.saga.event.ShipmentCreationFailed
import com.hartmann.domain.model.Order
import com.hartmann.domain.model.OrderStatus
import com.hartmann.domain.value.Currency
import com.hartmann.domain.value.Money
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class FulfillOrderSagaImpl(
    private val orderRepository: OrderRepository,
    private val eventProducer: OrderEventProducer,
) : FulfillOrderSaga {

    @Transactional
    override fun onPaymentCompleted(event: PaymentCompleted, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val updatedOrder = order.confirmPayment(event.paymentId)

        orderRepository.save(updatedOrder)
        eventProducer.publishCreateShipment(correlationId, updatedOrder.id, updatedOrder.address)
    }

    @Transactional
    override fun onPaymentFailed(event: PaymentFailed, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val updatedOrder = order.failPayment()

        orderRepository.save(updatedOrder)
        cancelReservations(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentCreationFailed(event: ShipmentCreationFailed, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val updatedOrder = order.failShipmentCreation()

        orderRepository.save(updatedOrder)
        cancelReservations(updatedOrder, correlationId)
        requestRefund(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentCreated(orderId: Long, shipmentId: Long, trackingNumber: String, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.createShipment(shipmentId, trackingNumber)

        orderRepository.save(updatedOrder)
        eventProducer.publishHoldReservation(correlationId, updatedOrder.id)
    }

    @Transactional
    override fun onShipmentInDelivery(orderId: Long, shipmentId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.startDelivery(shipmentId)

        orderRepository.save(updatedOrder)
        eventProducer.publishFulfillReservation(correlationId, updatedOrder.id)
    }

    @Transactional
    override fun onShipmentDelivered(orderId: Long) {
        val order = orderRepository.getOrder(orderId)
        orderRepository.save(order.deliver())
    }

    @Transactional
    override fun onShipmentCancelled(orderId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val wasInDelivery = order.status == OrderStatus.IN_DELIVERY
        val updatedOrder = order.cancelShipment()

        orderRepository.save(updatedOrder)
        if (!wasInDelivery) {
            cancelReservations(updatedOrder, correlationId)
        }
        requestRefund(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentLost(orderId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.loseShipment()

        orderRepository.save(updatedOrder)
        requestRefund(updatedOrder, correlationId)
    }

    @Transactional
    override fun onShipmentReturnedToInventory(orderId: Long, correlationId: String) {
        val order = orderRepository.getOrder(orderId)
        val updatedOrder = order.returnToStock()
        orderRepository.save(updatedOrder)

        requestRefund(updatedOrder, correlationId)
        val items = updatedOrder.items.map { RestockItemDto(it.productId, it.quantity.toInt()) }
        eventProducer.publishRestock(correlationId, updatedOrder.id, items)
    }

    @Transactional
    override fun onRefundCompleted(event: RefundCompleted, correlationId: String) {
        val order = orderRepository.getOrder(event.orderId)
        val amount = Money(event.amount, Currency.fromCode(event.currency))

        orderRepository.save(order.confirmRefund(amount))
    }

    private fun cancelReservations(order: Order, correlationId: String) {
        eventProducer.publishCancelReservation(correlationId, order.id)
    }

    private fun requestRefund(order: Order, correlationId: String) {
        val paymentId = requireNotNull(order.paymentId) { "order ${order.id} has no paymentId to refund" }
        val total = order.totalPrice()
        eventProducer.publishRefundRequested(correlationId, order.id, paymentId, total.amount, total.currency.name)
    }
}
