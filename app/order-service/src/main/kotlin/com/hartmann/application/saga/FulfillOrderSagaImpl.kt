package com.hartmann.application.saga

import com.hartmann.application.port.`in`.saga.FulfillOrderSaga
import com.hartmann.application.port.out.kafka.OrderEventProducer
import com.hartmann.application.port.out.repository.OrderRepository
import com.hartmann.application.saga.event.*
import com.hartmann.domain.model.OrderStatus
import org.springframework.stereotype.Component

@Component
class FulfillOrderSagaImpl(
    private val orderRepository: OrderRepository,
    private val eventProducer: OrderEventProducer,
) : FulfillOrderSaga {
    override fun onStockReserved(event: StockReserved) {
        val order = orderRepository.getOrder(event.orderId)
        if (order.status != OrderStatus.PENDING) return
        val updated = order.copy(
            reservationId = event.reservationId, status = OrderStatus.STOCK_RESERVED,
            statusDescription = "Stock reserved")
        orderRepository.save(updated)
        eventProducer.publish(PaymentRequested(updated.id, event.reservationId))
    }

    override fun onStockRejected(event: StockRejected) {
        val order = orderRepository.getOrder(event.orderId)
        if (order.status != OrderStatus.PENDING) return
        orderRepository.save(order.copy(status = OrderStatus.CANCELLED, statusDescription = "Stock rejected"))
    }

    override fun onPaymentCompleted(event: PaymentCompleted) {
        val order = orderRepository.getOrder(event.orderId)
        if (order.status != OrderStatus.STOCK_RESERVED) return
        val updated = order.copy(paymentId = event.paymentId, status = OrderStatus.PAID,
            statusDescription = "Payment completed")
        orderRepository.save(updated)
        eventProducer.publish(ShipmentRequested(updated.id, event.paymentId))
    }

    override fun onPaymentFailed(event: PaymentFailed) {
        val order = orderRepository.getOrder(event.orderId)
        if (order.status != OrderStatus.STOCK_RESERVED) return
        orderRepository.save(order.copy(status = OrderStatus.CANCELLED, statusDescription = "Payment failed"))
        eventProducer.publish(OrderCancelled(order.id))
    }

    override fun onShipmentCreated(event: ShipmentCreated) {
        val order = orderRepository.getOrder(event.orderId)
        if (order.status != OrderStatus.PAID) return
        orderRepository.save(order.copy(shipmentId = event.shipmentId,
            status = OrderStatus.SHIPMENT_CREATED, statusDescription = "Shipment created"))
    }

    override fun onShipmentFailed(event: ShipmentFailed) {
        val order = orderRepository.getOrder(event.orderId)
        if (order.status != OrderStatus.PAID) return
        orderRepository.save(order.copy(status = OrderStatus.CANCELLED, statusDescription = "Shipment failed"))
        eventProducer.publish(RefundRequested(order.id, requireNotNull(order.paymentId)))
        eventProducer.publish(OrderCancelled(order.id))
    }
}
