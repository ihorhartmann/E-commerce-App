package com.hartmann.usecase.port.out.kafka

import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import com.hartmann.entities.value.Address
import java.math.BigDecimal
import java.util.UUID

interface OrderKafkaProducer {
    fun publishCancelReservation(correlationId: String, orderId: Long)
    fun publishFulfillReservation(correlationId: String, orderId: Long)
    fun publishHoldReservation(correlationId: String, orderId: Long)
    fun publishRestock(correlationId: String, orderId: Long, items: List<RestockItemDto>)
    fun publishCreateShipment(correlationId: String, orderId: Long, customerId: UUID, address: Address)
    fun publishRefundRequested(correlationId: String, orderId: Long, amount: BigDecimal, currency: String)
}
