package com.hartmann.application.port.out.kafka

import com.hartmann.application.port.out.kafka.dto.RestockItemDto
import com.hartmann.domain.value.Address
import java.math.BigDecimal

interface OrderEventProducer {
    fun publishCancelReservation(correlationId: String, orderId: Long)
    fun publishFulfillReservation(correlationId: String, orderId: Long)
    fun publishHoldReservation(correlationId: String, orderId: Long)
    fun publishRestock(correlationId: String, orderId: Long, items: List<RestockItemDto>)
    fun publishCreateShipment(correlationId: String, orderId: Long, address: Address)
    fun publishRefundRequested(correlationId: String, orderId: Long, amount: BigDecimal, currency: String)
}
