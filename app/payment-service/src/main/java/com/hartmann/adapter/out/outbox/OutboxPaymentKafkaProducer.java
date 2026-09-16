package com.hartmann.adapter.out.outbox;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.out.outbox.dto.OrderIdEvent;
import com.hartmann.adapter.out.outbox.dto.RefundCompletedEvent;
import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.outbox.OutboxMessageRepository;
import com.hartmann.application.port.out.outbox.dto.OutboxMessage;
import com.hartmann.domain.value.Money;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxPaymentKafkaProducer implements PaymentKafkaProducer {
    private final OutboxMessageRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxPaymentKafkaProducer(OutboxMessageRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPaymentCompleted(String correlationId, long orderId) {
        insert(correlationId, KafkaConst.PAYMENT_COMPLETED_EVENT, orderId, new OrderIdEvent(orderId));
    }

    @Override
    public void publishPaymentExpired(String correlationId, long orderId) {
        insert(correlationId, KafkaConst.PAYMENT_EXPIRED_EVENT, orderId, new OrderIdEvent(orderId));
    }

    @Override
    public void publishRefundCompleted(String correlationId, long orderId, Money amount) {
        insert(correlationId, KafkaConst.REFUND_COMPLETED_EVENT, orderId, RefundCompletedEvent.from(orderId, amount));
    }

    @Override
    public void publishRefundFailed(String correlationId, long orderId) {
        insert(correlationId, KafkaConst.REFUND_FAILED_EVENT, orderId, new OrderIdEvent(orderId));
    }

    private void insert(String correlationId, String messageType, long orderId, Object payload) {
        var key = KafkaUtils.formatKafkaId(orderId);
        var message = new OutboxMessage(null, correlationId, messageType, key, objectMapper.writeValueAsString(payload));
        outboxRepository.insert(message);
    }
}
