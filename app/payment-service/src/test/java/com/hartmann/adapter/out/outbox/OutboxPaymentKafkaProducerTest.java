package com.hartmann.adapter.out.outbox;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.out.outbox.dto.OrderIdEvent;
import com.hartmann.adapter.out.outbox.dto.RefundCompletedEvent;
import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.out.outbox.OutboxMessageRepository;
import com.hartmann.application.port.out.outbox.dto.OutboxMessage;
import com.hartmann.application.port.out.tracing.TraceContextPropagator;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OutboxPaymentKafkaProducerTest {

    @Mock
    private OutboxMessageRepository outboxRepository;

    @Mock
    private TraceContextPropagator traceContextPropagator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxPaymentKafkaProducer producer;

    @BeforeEach
    void setUp() {
        producer = new OutboxPaymentKafkaProducer(outboxRepository, objectMapper, traceContextPropagator);
    }

    private OutboxMessage wantOutboxMessage(String correlationId, String messageType, long orderId, Object payload, String traceparent) {
        return OutboxMessage.createNew(correlationId, messageType, KafkaUtils.formatKafkaId(orderId), objectMapper.writeValueAsString(payload), traceparent);
    }

    @Test
    void publishPaymentCompleted_insertsAPaymentCompletedEventKeyedByTheOrderId() {
        var correlationId = "corr-1";
        var orderId = 42L;

        producer.publishPaymentCompleted(correlationId, orderId);

        verify(outboxRepository, times(1))
                .insert(wantOutboxMessage(correlationId, KafkaConst.PAYMENT_COMPLETED_EVENT, orderId, new OrderIdEvent(orderId), null));
        verify(traceContextPropagator, times(1)).currentTraceparent();
    }

    @Test
    void publishPaymentCompleted_keepsTheTraceparentOfTheSpanTheMessageIsWrittenIn() {
        var correlationId = "corr-6";
        var orderId = 43L;
        var traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        when(traceContextPropagator.currentTraceparent()).thenReturn(traceparent);

        producer.publishPaymentCompleted(correlationId, orderId);

        verify(outboxRepository, times(1))
                .insert(wantOutboxMessage(correlationId, KafkaConst.PAYMENT_COMPLETED_EVENT, orderId, new OrderIdEvent(orderId), traceparent));
        verify(traceContextPropagator, times(1)).currentTraceparent();
    }

    @Test
    void publishPaymentExpired_insertsAPaymentExpiredEventKeyedByTheOrderId() {
        var correlationId = "corr-3";
        var orderId = 8L;

        producer.publishPaymentExpired(correlationId, orderId);

        verify(outboxRepository, times(1))
                .insert(wantOutboxMessage(correlationId, KafkaConst.PAYMENT_EXPIRED_EVENT, orderId, new OrderIdEvent(orderId), null));
        verify(traceContextPropagator, times(1)).currentTraceparent();
    }

    @Test
    void publishRefundCompleted_insertsARefundCompletedEventWithTheMappedAmount() {
        var correlationId = "corr-4";
        var orderId = 13L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);

        producer.publishRefundCompleted(correlationId, orderId, amount);

        verify(outboxRepository, times(1))
                .insert(wantOutboxMessage(correlationId, KafkaConst.REFUND_COMPLETED_EVENT, orderId, RefundCompletedEvent.from(orderId, amount), null));
        verify(traceContextPropagator, times(1)).currentTraceparent();
    }

    @Test
    void publishRefundFailed_insertsARefundFailedEventKeyedByTheOrderId() {
        var correlationId = "corr-5";
        var orderId = 99L;

        producer.publishRefundFailed(correlationId, orderId);

        verify(outboxRepository, times(1))
                .insert(wantOutboxMessage(correlationId, KafkaConst.REFUND_FAILED_EVENT, orderId, new OrderIdEvent(orderId), null));
        verify(traceContextPropagator, times(1)).currentTraceparent();
    }
}
