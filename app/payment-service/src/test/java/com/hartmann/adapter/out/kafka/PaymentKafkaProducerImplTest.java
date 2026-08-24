package com.hartmann.adapter.out.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.out.kafka.dto.PaymentCompletedEvent;
import com.hartmann.adapter.out.kafka.dto.PaymentFailedEvent;
import com.hartmann.adapter.out.kafka.dto.RefundCompletedEvent;
import com.hartmann.adapter.out.kafka.dto.RefundFailedEvent;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentKafkaProducerImplTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentKafkaProducerImpl producer;

    @BeforeEach
    void setUp() {
        producer = new PaymentKafkaProducerImpl(kafkaTemplate, objectMapper);
    }

    private static String headerValue(ProducerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private ProducerRecord<String, String> captureSentRecord() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void publishPaymentCompleted_sendsAPaymentCompletedEventKeyedByTheOrderId() {
        var orderId = 42L;

        producer.publishPaymentCompleted(orderId);

        var record = captureSentRecord();
        assertThat(record.topic()).isEqualTo(KafkaConst.PAYMENT_EVENTS_TOPIC);
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(headerValue(record, KafkaConst.MESSAGE_TYPE)).isEqualTo(KafkaConst.PAYMENT_COMPLETED_EVENT);
        assertThat(objectMapper.readValue(record.value(), PaymentCompletedEvent.class))
                .isEqualTo(new PaymentCompletedEvent(orderId));
    }

    @Test
    void publishPaymentFailed_sendsAPaymentFailedEventKeyedByTheOrderId() {
        var orderId = 7L;

        producer.publishPaymentFailed(orderId);

        var record = captureSentRecord();
        assertThat(record.topic()).isEqualTo(KafkaConst.PAYMENT_EVENTS_TOPIC);
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(headerValue(record, KafkaConst.MESSAGE_TYPE)).isEqualTo(KafkaConst.PAYMENT_FAILED_EVENT);
        assertThat(objectMapper.readValue(record.value(), PaymentFailedEvent.class))
                .isEqualTo(new PaymentFailedEvent(orderId));
    }

    @Test
    void publishRefundCompleted_sendsARefundCompletedEventWithTheMappedAmount() {
        var orderId = 13L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);

        producer.publishRefundCompleted(orderId, amount);

        var record = captureSentRecord();
        assertThat(record.topic()).isEqualTo(KafkaConst.PAYMENT_EVENTS_TOPIC);
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(headerValue(record, KafkaConst.MESSAGE_TYPE)).isEqualTo(KafkaConst.REFUND_COMPLETED_EVENT);
        assertThat(objectMapper.readValue(record.value(), RefundCompletedEvent.class))
                .isEqualTo(RefundCompletedEvent.from(orderId, amount));
    }

    @Test
    void publishRefundFailed_sendsARefundFailedEventKeyedByTheOrderId() {
        var orderId = 99L;

        producer.publishRefundFailed(orderId);

        var record = captureSentRecord();
        assertThat(record.topic()).isEqualTo(KafkaConst.PAYMENT_EVENTS_TOPIC);
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(headerValue(record, KafkaConst.MESSAGE_TYPE)).isEqualTo(KafkaConst.REFUND_FAILED_EVENT);
        assertThat(objectMapper.readValue(record.value(), RefundFailedEvent.class))
                .isEqualTo(new RefundFailedEvent(orderId));
    }
}
