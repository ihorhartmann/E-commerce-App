package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.exception.InvalidMessageHeaderException;
import com.hartmann.adapter.in.kafka.exception.MalformedPayloadException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.out.repository.exception.PaymentNotFoundException;
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
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
    void onMessage_putsTheMessagesIdsInTheMdcWhileItIsHandledAndTakesThemOutAfter() {
        var paymentId = 2L;
        var orderId = 101L;
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        var messageId = UUID.randomUUID();
        var correlationId = "corr-mdc";
        var actualMdc = new AtomicReference<Map<String, String>>();
        when(paymentService.getByOrderId(orderId)).thenAnswer(_ -> {
            actualMdc.set(MDC.getCopyOfContextMap());
            return testPayment(paymentId, orderId);
        });

        consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId.toString(), correlationId, payload);

        var expectedMdc = Map.of(
                KafkaConst.MESSAGE_TYPE, KafkaConst.REFUND_REQUESTED_COMMAND,
                KafkaConst.MESSAGE_ID, messageId.toString(),
                KafkaConst.CORRELATION_ID, correlationId);
        assertThat(actualMdc.get()).isEqualTo(expectedMdc);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        verify(paymentService, times(1)).getByOrderId(orderId);
        verify(refundService, times(1)).submitRefund(messageId, correlationId, paymentId,
                new Money(new BigDecimal("40.00"), Currency.USD));
    }

    @Test
    void onMessage_takesTheMessagesIdsOutOfTheMdcWhenHandlingFails() {
        var orderId = 102L;
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        var exception = PaymentNotFoundException.withOrderId(orderId);
        when(paymentService.getByOrderId(orderId)).thenThrow(exception);

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, UUID.randomUUID().toString(),
                "corr-mdc-failure", payload)).isSameAs(exception);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        verify(paymentService, times(1)).getByOrderId(orderId);
        verifyNoInteractions(refundService);
    }

    @Test
    void onMessage_ignoresUnrecognizedMessageTypes() {
        consumer.onMessage("SomeUnrelatedEvent", UUID.randomUUID().toString(), "corr-2", "{}");

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_throwsAMalformedPayloadExceptionWithoutCallingEitherServiceWhenThePayloadIsMalformedJson() {
        var messageId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId, "corr-3", "not json"))
                .isInstanceOf(MalformedPayloadException.class)
                .hasCauseInstanceOf(JacksonException.class);

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_throwsAnInvalidMessageHeaderExceptionWithoutCallingEitherServiceWhenAHeaderIsMissing() {
        var payload = "{\"orderId\":100,\"amount\":40.00,\"currency\":\"USD\"}";

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, null, "corr-missing-id", payload))
                .isInstanceOf(InvalidMessageHeaderException.class)
                .extracting(exception -> ((InvalidMessageHeaderException) exception).attributes().get("header"))
                .isEqualTo(KafkaConst.MESSAGE_ID);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_throwsAnInvalidMessageHeaderExceptionWithoutCallingEitherServiceWhenTheMessageIdIsNotAUuid() {
        var payload = "{\"orderId\":100,\"amount\":40.00,\"currency\":\"USD\"}";
        var messageId = "msg-4";

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId, "corr-4", payload))
                .isInstanceOf(InvalidMessageHeaderException.class);

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void onMessage_rethrowsAPermanentServiceFailureAsItIs() {
        var orderId = 100L;
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        var expectedException = PaymentNotFoundException.withOrderId(orderId);
        when(paymentService.getByOrderId(orderId)).thenThrow(expectedException);

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, UUID.randomUUID().toString(), "corr-5", payload))
                .isSameAs(expectedException);

        verify(paymentService, times(1)).getByOrderId(orderId);
        verifyNoInteractions(refundService);
    }

    @Test
    void onMessage_rethrowsARetryableServiceFailureAsItIs() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);
        var payload = "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}";
        var messageId = UUID.randomUUID();
        var correlationId = "corr-6";
        var expectedException = PaymentGatewayUnavailableException.ofRefund("pi_test", paymentId, new RuntimeException());
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));
        when(refundService.submitRefund(messageId, correlationId, paymentId, amount)).thenThrow(expectedException);

        assertThatThrownBy(() -> consumer.onMessage(KafkaConst.REFUND_REQUESTED_COMMAND, messageId.toString(), correlationId, payload))
                .isSameAs(expectedException);

        verify(paymentService, times(1)).getByOrderId(orderId);
        verify(refundService, times(1)).submitRefund(messageId, correlationId, paymentId, amount);
    }
}
