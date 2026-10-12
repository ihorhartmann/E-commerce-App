package com.hartmann.interfaceadapter.out.outbox

import com.hartmann.entities.value.Address
import com.hartmann.interfaceadapter.out.kafka.dto.CancelReservationCommand
import com.hartmann.interfaceadapter.out.kafka.dto.CancelShipmentCommand
import com.hartmann.interfaceadapter.out.kafka.dto.FulfillReservationCommand
import com.hartmann.interfaceadapter.out.kafka.dto.HoldReservationCommand
import com.hartmann.interfaceadapter.out.kafka.dto.RefundRequestedCommand
import com.hartmann.interfaceadapter.out.kafka.dto.toCreateShipmentCommand
import com.hartmann.interfaceadapter.out.kafka.dto.toRestockCommand
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.out.kafka.OrderKafkaProducer
import com.hartmann.usecase.port.out.kafka.dto.RestockItemDto
import com.hartmann.usecase.port.out.outbox.OutboxMessageRepository
import com.hartmann.usecase.port.out.outbox.dto.OutboxMessage
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * Implements OrderKafkaProducer by writing to the transactional outbox instead of publishing to
 * Kafka directly - OutboxMessageRelay is what actually delivers the message afterwards, so the
 * saga's DB mutation and the outbox insert can commit atomically in the same transaction.
 */
@Component
class OutboxOrderKafkaProducer(
    private val outboxRepository: OutboxMessageRepository,
    private val objectMapper: ObjectMapper,
) : OrderKafkaProducer {

    override fun publishCancelReservation(correlationId: String, orderId: Long) {
        insert(correlationId, KafkaConst.INVENTORY_COMMANDS_TOPIC, KafkaConst.CANCEL_RESERVATION_COMMAND, orderId.toString(), CancelReservationCommand(orderId))
    }

    override fun publishFulfillReservation(correlationId: String, orderId: Long) {
        insert(correlationId, KafkaConst.INVENTORY_COMMANDS_TOPIC, KafkaConst.FULFILL_RESERVATION_COMMAND, orderId.toString(), FulfillReservationCommand(orderId))
    }

    override fun publishHoldReservation(correlationId: String, orderId: Long) {
        insert(correlationId, KafkaConst.INVENTORY_COMMANDS_TOPIC, KafkaConst.HOLD_RESERVATION_COMMAND, orderId.toString(), HoldReservationCommand(orderId))
    }

    override fun publishRestock(correlationId: String, orderId: Long, items: List<RestockItemDto>) {
        insert(correlationId, KafkaConst.INVENTORY_COMMANDS_TOPIC, KafkaConst.RESTOCK_COMMAND, orderId.toString(), toRestockCommand(orderId, items))
    }

    override fun publishCreateShipment(correlationId: String, orderId: Long, address: Address) {
        insert(correlationId, KafkaConst.SHIPPING_COMMANDS_TOPIC, KafkaConst.CREATE_SHIPMENT_COMMAND, orderId.toString(), toCreateShipmentCommand(orderId, address))
    }

    override fun publishCancelShipment(correlationId: String, orderId: Long) {
        insert(correlationId, KafkaConst.SHIPPING_COMMANDS_TOPIC, KafkaConst.CANCEL_SHIPMENT_COMMAND, orderId.toString(), CancelShipmentCommand(orderId))
    }

    override fun publishRefundRequested(correlationId: String, orderId: Long, amount: BigDecimal, currency: String) {
        insert(correlationId, KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND, orderId.toString(), RefundRequestedCommand(orderId, amount, currency))
    }

    private fun insert(correlationId: String, topic: String, messageType: String, key: String, payload: Any) {
        val message = OutboxMessage.createNew(
            correlationId = correlationId,
            messageType = messageType,
            topic = topic,
            key = key,
            payload = objectMapper.writeValueAsString(payload),
        )
        outboxRepository.insert(message)
    }
}
