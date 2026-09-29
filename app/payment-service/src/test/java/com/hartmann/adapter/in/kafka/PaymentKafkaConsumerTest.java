package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.exception.InvalidMessageHeaderException;
import com.hartmann.adapter.in.kafka.exception.NonRetryableException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
                PaymentStatus.CONFIRMED, Money.zero(Currency.USD), 0L, now, now, now.plusSeconds(900));
    }

    @Test
    void onMessage_dispatchesRefundRequestedToTheRefundServiceKeyedByTheMessageId() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        var messageId = UUID.randomUUID();
        var correlationId = "corr-1";
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));

        consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId.toString(), correlationId, payload);

        verify(paymentService, times(1)).getByOrderId(orderId);
        verify(refundService, times(1)).submitRefund(messageId, correlationId, paymentId, amount);
    }

    @Test
    void onMessage_ignoresUnrecognizedMessageTypes() {
        consumer.onMessage("SomeUnrelatedEvent", UUID.randomUUID().toString(), "corr-2", "{}");

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_throwsANonRetryableExceptionWithoutCallingEitherServiceWhenThePayloadIsMalformedJson() {
        var messageId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId, "corr-3", "not json"))
                .isInstanceOf(NonRetryableException.class)
                .hasCauseInstanceOf(JacksonException.class);

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_throwsANonRetryableExceptionWithoutCallingEitherServiceWhenTheMessageIdIsNotAUuid() {
        var payload = "{\"orderId\":100,\"amount\":40.00,\"currency\":\"USD\"}";
        var messageId = "msg-4";

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId, "corr-4", payload))
                .isInstanceOf(NonRetryableException.class)
                .hasCauseInstanceOf(InvalidMessageHeaderException.class);

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_wrapsANonRetryableServiceFailureInANonRetryableException() {
        var orderId = 100L;
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        var cause = new ObjectNotFoundException("no payment for order " + orderId);
        when(paymentService.getByOrderId(orderId)).thenThrow(cause);

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, UUID.randomUUID().toString(), "corr-5", payload))
                .isInstanceOf(NonRetryableException.class)
                .hasCause(cause);

        verify(paymentService, times(1)).getByOrderId(orderId);
        verifyNoInteractions(refundService);
    }

    @Test
    void onMessage_rethrowsARetryableServiceFailureUnwrapped() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        var messageId = UUID.randomUUID();
        var correlationId = "corr-6";
        var expectedException = new PaymentGatewayUnavailableException("Stripe unavailable", new RuntimeException());
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));
        when(refundService.submitRefund(messageId, correlationId, paymentId, amount)).thenThrow(expectedException);

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId.toString(), correlationId, payload))
                .isSameAs(expectedException);

        verify(paymentService, times(1)).getByOrderId(orderId);
        verify(refundService, times(1)).submitRefund(messageId, correlationId, paymentId, amount);
    }
}
