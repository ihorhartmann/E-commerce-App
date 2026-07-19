package com.hartmann.infrastructure.kafka

import com.hartmann.application.kafka.KafkaConst
import com.hartmann.application.port.out.kafka.OrderEventProducer
import com.hartmann.application.port.out.kafka.dto.RestockItemDto
import com.hartmann.domain.value.Address
import com.hartmann.infrastructure.kafka.dto.AddressDto
import com.hartmann.infrastructure.kafka.dto.CancelReservationMessage
import com.hartmann.infrastructure.kafka.dto.CreateShipmentMessage
import com.hartmann.infrastructure.kafka.dto.FulfillReservationMessage
import com.hartmann.infrastructure.kafka.dto.HoldReservationMessage
import com.hartmann.infrastructure.kafka.dto.RefundRequestedMessage
import com.hartmann.infrastructure.kafka.dto.RestockItemMessage
import com.hartmann.infrastructure.kafka.dto.RestockMessage
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
            KafkaConst.INVENTORY_COMMANDS,
            KafkaConst.CANCEL_RESERVATION_EVENT_TYPE,
            correlationId,
            orderId,
            CancelReservationMessage(orderId),
        )
    }

    override fun publishFulfillReservation(correlationId: String, orderId: Long) {
        send(
            KafkaConst.INVENTORY_COMMANDS,
            KafkaConst.FULFILL_RESERVATION_EVENT_TYPE,
            correlationId,
            orderId,
            FulfillReservationMessage(orderId),
        )
    }

    override fun publishHoldReservation(correlationId: String, orderId: Long) {
        send(
            KafkaConst.INVENTORY_COMMANDS,
            KafkaConst.HOLD_RESERVATION_EVENT_TYPE,
            correlationId,
            orderId,
            HoldReservationMessage(orderId),
        )
    }

    override fun publishRestock(correlationId: String, orderId: Long, items: List<RestockItemDto>) {
        send(
            KafkaConst.INVENTORY_COMMANDS,
            KafkaConst.RESTOCK_EVENT_TYPE,
            correlationId,
            orderId,
            RestockMessage(orderId, items.map { RestockItemMessage(it.productId, it.quantity) }),
        )
    }

    override fun publishCreateShipment(correlationId: String, orderId: Long, address: Address) {
        send(
            KafkaConst.SHIPPING_COMMANDS,
            KafkaConst.CREATE_SHIPMENT_EVENT_TYPE,
            correlationId,
            orderId,
            CreateShipmentMessage(orderId, AddressDto.fromDomain(address)),
        )
    }

    override fun publishRefundRequested(
        correlationId: String,
        orderId: Long,
        paymentId: Long,
        amount: BigDecimal,
        currency: String,
    ) {
        send(
            KafkaConst.PAYMENT_COMMANDS,
            KafkaConst.REFUND_REQUESTED_EVENT_TYPE,
            correlationId,
            orderId,
            RefundRequestedMessage(orderId, paymentId, amount, currency),
        )
    }

    // key = orderId (kept as its own parameter, distinct from correlationId, even though this
    // service's convention happens to set correlationId to the same value) so partitioning stays
    // keyed to the aggregate, not to whatever tracing convention correlationId follows.
    private fun send(topic: String, eventType: String, correlationId: String, orderId: Long, payload: Any) {
        val record = ProducerRecord<String, String>(topic, orderId.toString(), objectMapper.writeValueAsString(payload))
        record.headers()
            .add(RecordHeader(KafkaConst.EVENT_TYPE, eventType.toByteArray(StandardCharsets.UTF_8)))
            .add(RecordHeader(KafkaConst.CORRELATION_ID, correlationId.toByteArray(StandardCharsets.UTF_8)))
            .add(
                RecordHeader(
                    KafkaConst.EVENT_ID,
                    UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8),
                )
            )

        LOG.debug("sending {} to topic={} correlationId={}", eventType, topic, correlationId)
        kafkaTemplate.send(record)
        LOG.info("successfully sent {} to topic={} correlationId={}", eventType, topic, correlationId)
    }
}
