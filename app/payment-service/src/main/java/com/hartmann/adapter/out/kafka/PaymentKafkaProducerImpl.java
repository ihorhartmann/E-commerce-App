package com.hartmann.adapter.out.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.out.kafka.dto.PaymentCompletedEvent;
import com.hartmann.adapter.out.kafka.dto.PaymentFailedEvent;
import com.hartmann.adapter.out.kafka.dto.RefundCompletedEvent;
import com.hartmann.adapter.out.kafka.dto.RefundFailedEvent;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.domain.value.Money;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class PaymentKafkaProducerImpl implements PaymentKafkaProducer {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentKafkaProducerImpl.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentKafkaProducerImpl(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPaymentCompleted(long orderId) {
        send(KafkaConst.PAYMENT_COMPLETED_EVENT_TYPE, String.valueOf(orderId), orderId, new PaymentCompletedEvent(orderId));
    }

    @Override
    public void publishPaymentFailed(long orderId) {
        send(KafkaConst.PAYMENT_FAILED_EVENT_TYPE, String.valueOf(orderId), orderId, new PaymentFailedEvent(orderId));
    }

    @Override
    public void publishRefundCompleted(long orderId, Money amount) {
        var event = new RefundCompletedEvent(orderId, amount.amount(), amount.currency().name());
        send(KafkaConst.REFUND_COMPLETED_EVENT_TYPE, String.valueOf(orderId), orderId, event);
    }

    @Override
    public void publishRefundFailed(long orderId) {
        send(KafkaConst.REFUND_FAILED_EVENT_TYPE, String.valueOf(orderId), orderId, new RefundFailedEvent(orderId));
    }

    // key = orderId (kept as its own parameter, distinct from correlationId, even though this
    // service's convention happens to set correlationId to the same value) so partitioning stays
    // keyed to the aggregate, not to whatever tracing convention correlationId follows.
    private void send(String eventType, String correlationId, long orderId, Object payload) {
        var record = new ProducerRecord<>(KafkaConst.PAYMENT_EVENTS_TOPIC, String.valueOf(orderId), objectMapper.writeValueAsString(payload));
        record.headers()
                .add(new RecordHeader(KafkaConst.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(KafkaConst.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(KafkaConst.EVENT_ID,
                        UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));

        LOG.debug("sending {} to topic={} correlationId={}", eventType, KafkaConst.PAYMENT_EVENTS_TOPIC, correlationId);
        kafkaTemplate.send(record);
        LOG.info("successfully sent {} to topic={} correlationId={}", eventType, KafkaConst.PAYMENT_EVENTS_TOPIC, correlationId);
    }
}
