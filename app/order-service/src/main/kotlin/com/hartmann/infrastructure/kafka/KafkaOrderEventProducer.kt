package com.hartmann.infrastructure.kafka

import com.hartmann.application.kafka.KafkaConst
import com.hartmann.application.port.out.kafka.OrderEventProducer
import com.hartmann.application.port.out.kafka.dto.RestockItemDto
import com.hartmann.domain.value.Address
import com.hartmann.infrastructure.kafka.dto.AddressDto
import com.hartmann.infrastructure.kafka.dto.CancelReservationCommand
import com.hartmann.infrastructure.kafka.dto.CreateShipmentCommand
import com.hartmann.infrastructure.kafka.dto.FulfillReservationCommand
import com.hartmann.infrastructure.kafka.dto.HoldReservationCommand
import com.hartmann.infrastructure.kafka.dto.RefundRequestedCommand
import com.hartmann.infrastructure.kafka.dto.RestockCommand
import com.hartmann.infrastructure.kafka.dto.RestockItem
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.UUID

@Component
class KafkaOrderEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : OrderEventProducer {
    companion object {
        private val LOG = LoggerFactory.getLogger(KafkaOrderEventProducer::class.java)
    }

    override fun publishCancelReservation(correlationId: String, orderId: Long) {
        send(
            KafkaConst.INVENTORY_COMMANDS_TOPIC,
            KafkaConst.CANCEL_RESERVATION_COMMAND,
            correlationId,
            orderId,
            CancelReservationCommand(orderId),
        )
    }

    override fun publishFulfillReservation(correlationId: String, orderId: Long) {
        send(
            KafkaConst.INVENTORY_COMMANDS_TOPIC,
            KafkaConst.FULFILL_RESERVATION_COMMAND,
            correlationId,
            orderId,
            FulfillReservationCommand(orderId),
        )
    }

    override fun publishHoldReservation(correlationId: String, orderId: Long) {
        send(
            KafkaConst.INVENTORY_COMMANDS_TOPIC,
            KafkaConst.HOLD_RESERVATION_COMMAND,
            correlationId,
            orderId,
            HoldReservationCommand(orderId),
        )
    }

    override fun publishRestock(correlationId: String, orderId: Long, items: List<RestockItemDto>) {
        send(
            KafkaConst.INVENTORY_COMMANDS_TOPIC,
            KafkaConst.RESTOCK_COMMAND,
            correlationId,
            orderId,
            RestockCommand(orderId, items.map { RestockItem(it.productId, it.quantity) }),
        )
    }

    override fun publishCreateShipment(correlationId: String, orderId: Long, address: Address) {
        send(
            KafkaConst.SHIPPING_COMMANDS_TOPIC,
            KafkaConst.CREATE_SHIPMENT_COMMAND,
            correlationId,
            orderId,
            CreateShipmentCommand(orderId, AddressDto.fromDomain(address)),
        )
    }

    override fun publishRefundRequested(
        correlationId: String,
        orderId: Long,
        amount: BigDecimal,
        currency: String,
    ) {
        send(
            KafkaConst.PAYMENT_COMMANDS_TOPIC,
            KafkaConst.REFUND_REQUESTED_COMMAND,
            correlationId,
            orderId,
            RefundRequestedCommand(orderId, amount, currency),
        )
    }

    // key = orderId (kept as its own parameter, distinct from correlationId, even though this
    // service's convention happens to set correlationId to the same value) so partitioning stays
    // keyed to the aggregate, not to whatever tracing convention correlationId follows.
    private fun send(topic: String, messageType: String, correlationId: String, orderId: Long, payload: Any) {
        val record = ProducerRecord<String, String>(topic, orderId.toString(), objectMapper.writeValueAsString(payload))
        record.headers()
            .add(RecordHeader(KafkaConst.MESSAGE_TYPE, messageType.toByteArray(StandardCharsets.UTF_8)))
            .add(RecordHeader(KafkaConst.CORRELATION_ID, correlationId.toByteArray(StandardCharsets.UTF_8)))
            .add(
                RecordHeader(
                    KafkaConst.MESSAGE_ID,
                    UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8),
                )
            )

        LOG.debug("sending {} to topic={} correlationId={}", messageType, topic, correlationId)
        kafkaTemplate.send(record)
        LOG.info("successfully sent {} to topic={} correlationId={}", messageType, topic, correlationId)
    }
}
