package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentServiceImplTest {
    private static final long EXPIRES_IN_SECONDS = 900;

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private PaymentKafkaProducer paymentKafkaProducer;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(paymentRepository, paymentGateway, paymentKafkaProducer);
    }

    private Money testAmount() {
        return new Money(new BigDecimal("50.00"), Currency.USD);
    }

    private Payment testPayment(long id, long orderId, PaymentStatus status) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", testAmount(), status, List.of(), now, now, now.plusSeconds(EXPIRES_IN_SECONDS));
    }

    private Payment testInitiatedPayment(long id, long orderId) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, null, testAmount(), PaymentStatus.INITIATED, List.of(), now, null, now.plusSeconds(EXPIRES_IN_SECONDS));
    }

    private Payment testExpiredPaymentNeverSubmitted(long id, long orderId) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, null, testAmount(), PaymentStatus.EXPIRED, List.of(), now, now, now.plusSeconds(EXPIRES_IN_SECONDS));
    }

    @Test
    void getByOrderId_returnsThePaymentFromTheRepository() {
        var orderId = 100L;
        var payment = testPayment(1L, orderId, PaymentStatus.CONFIRMED);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);

        var result = paymentService.getByOrderId(orderId);

        assertThat(result).isEqualTo(payment);
    }

    @Test
    void submitPayment_createsAnInitiatedPaymentThenMarksItSubmittedWithTheGatewayResult() {
        var orderId = 100L;
        var initiatedId = 1L;
        var externalId = "pi_new";
        var initiatedPayment = testInitiatedPayment(initiatedId, orderId);
        var submittedPayment = testPayment(initiatedId, orderId, PaymentStatus.PENDING);
        when(paymentRepository.create(any())).thenReturn(initiatedPayment);
        when(paymentGateway.submitPayment(orderId, testAmount()))
                .thenReturn(new SubmitPaymentResult(externalId, "secret_123"));
        when(paymentRepository.markPending(initiatedId, externalId)).thenReturn(submittedPayment);

        var result = paymentService.submitPayment(orderId, testAmount());

        assertThat(result).isEqualTo(new PaymentIntentResult(submittedPayment, "secret_123"));

        var captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(1)).create(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(orderId);
        assertThat(captor.getValue().status()).isEqualTo(PaymentStatus.INITIATED);
        verify(paymentRepository, times(1)).markPending(initiatedId, externalId);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitPayment_marksTheInitiatedPaymentFailedAndRethrowsWhenTheGatewayCallFails() {
        var orderId = 100L;
        var initiatedId = 1L;
        var initiatedPayment = testInitiatedPayment(initiatedId, orderId);
        var expectedException = new PaymentGatewayUnavailableException("Stripe unavailable", new RuntimeException());
        when(paymentRepository.create(any())).thenReturn(initiatedPayment);
        when(paymentGateway.submitPayment(orderId, testAmount())).thenThrow(expectedException);

        assertThatThrownBy(() -> paymentService.submitPayment(orderId, testAmount()))
                .isSameAs(expectedException);

        verify(paymentRepository, times(1)).updateStatus(initiatedId, PaymentStatus.FAILED);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitPayment_marksTheInitiatedPaymentFailedOnAPermanentGatewayError() {
        var orderId = 100L;
        var initiatedId = 1L;
        var initiatedPayment = testInitiatedPayment(initiatedId, orderId);
        var expectedException = new PaymentGatewayException("invalid request", new RuntimeException());
        when(paymentRepository.create(any())).thenReturn(initiatedPayment);
        when(paymentGateway.submitPayment(orderId, testAmount())).thenThrow(expectedException);

        assertThatThrownBy(() -> paymentService.submitPayment(orderId, testAmount()))
                .isSameAs(expectedException);

        verify(paymentRepository, times(1)).updateStatus(initiatedId, PaymentStatus.FAILED);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesPaymentCompletedWhenStatusBecomesConfirmed() {
        var id = 1L;
        var orderId = 100L;
        var externalId = "pi_test";
        var status = PaymentStatus.CONFIRMED;
        var payment = testPayment(id, orderId, PaymentStatus.PENDING);
        var confirmedPayment = testPayment(id, orderId, status);
        when(paymentRepository.getByExternalId(externalId)).thenReturn(payment);
        when(paymentRepository.updateStatus(id, status)).thenReturn(confirmedPayment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isEqualTo(confirmedPayment);
        verify(paymentKafkaProducer, times(1)).publishPaymentCompleted(orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesNothingWhenStatusBecomesAttemptDeclined() {
        var id = 1L;
        var externalId = "pi_test";
        var status = PaymentStatus.ATTEMPT_DECLINED;
        var payment = testPayment(id, 100L, PaymentStatus.PENDING);
        var declinedPayment = testPayment(id, 100L, status);
        when(paymentRepository.getByExternalId(externalId)).thenReturn(payment);
        when(paymentRepository.updateStatus(id, status)).thenReturn(declinedPayment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isEqualTo(declinedPayment);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void expireOverduePayments_publishesPaymentExpiredForAnExpiredPaymentThatHadReachedTheGateway() {
        var orderId = 100L;
        var expiredPayment = testPayment(1L, orderId, PaymentStatus.EXPIRED);
        when(paymentRepository.expireOverdue()).thenReturn(List.of(expiredPayment));

        paymentService.expireOverduePayments();

        verify(paymentKafkaProducer, times(1)).publishPaymentExpired(orderId);
    }

    @Test
    void expireOverduePayments_publishesNothingForAnExpiredPaymentThatNeverReachedTheGateway() {
        var orderId = 100L;
        var expiredPayment = testExpiredPaymentNeverSubmitted(1L, orderId);
        when(paymentRepository.expireOverdue()).thenReturn(List.of(expiredPayment));

        paymentService.expireOverduePayments();

        verifyNoInteractions(paymentKafkaProducer);
    }
}
