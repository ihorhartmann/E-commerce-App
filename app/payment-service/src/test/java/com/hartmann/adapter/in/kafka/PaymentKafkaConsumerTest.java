package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentKafkaConsumerTest {

    @Mock
    private RefundService refundService;
    @Mock
    private PaymentService paymentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentKafkaConsumer(refundService, paymentService, objectMapper);
    }

    private Payment testPayment(long id, long orderId) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", new Money(new BigDecimal("100.00"), Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now);
    }

    @Test
    void onMessage_dispatchesRefundRequestedToTheRefundService() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));

        consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, "corr-1", payload);

        verify(refundService, times(1)).submitRefund(paymentId, amount);
    }

    @Test
    void onMessage_ignoresUnrecognizedMessageTypes() {
        consumer.onMessage("SomeUnrelatedEvent", "corr-2", "{}");

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_throwsWithoutCallingEitherServiceWhenThePayloadIsMalformedJson() {
        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, "corr-3", "not json"))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(paymentService, refundService);
    }
}
