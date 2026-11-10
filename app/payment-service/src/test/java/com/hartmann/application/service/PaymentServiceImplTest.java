package com.hartmann.application.service;

import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.PaymentCancellationReason;
import com.hartmann.application.port.out.http.dto.PaymentIntentState;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.exception.PaymentGatewayRejectedException;
import com.hartmann.application.port.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.tracing.CorrelationTagger;
import com.hartmann.application.service.exception.PaymentAlreadyConfirmedException;
import com.hartmann.application.service.exception.PaymentProcessingException;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    @Mock
    private CorrelationTagger correlationTagger;
    @Mock
    private PlatformTransactionManager transactionManager;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(paymentRepository, paymentGateway, paymentKafkaProducer, correlationTagger,
                transactionManager);
    }

    private Money testAmount() {
        return new Money(new BigDecimal("50.00"), Currency.USD);
    }

    private Payment testPayment(long id, long orderId, PaymentStatus status) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", testAmount(), status, Money.zero(Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS));
    }

    private Payment testInitiatedPayment(long id, long orderId) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, null, testAmount(), PaymentStatus.INITIATED, Money.zero(Currency.USD), 0L, now, null, now.plusSeconds(EXPIRES_IN_SECONDS));
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
        when(paymentRepository.markPending(initiatedId, initiatedPayment.version(), externalId)).thenReturn(submittedPayment);

        var result = paymentService.submitPayment(orderId, testAmount());

        assertThat(result).isEqualTo(new PaymentIntentResult(submittedPayment, "secret_123"));

        var captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(1)).create(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(orderId);
        assertThat(captor.getValue().status()).isEqualTo(PaymentStatus.INITIATED);
        verify(paymentRepository, times(1)).markPending(initiatedId, initiatedPayment.version(), externalId);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitPayment_marksTheInitiatedPaymentFailedAndRethrowsWhenTheGatewayCallFails() {
        var orderId = 100L;
        var initiatedId = 1L;
        var initiatedPayment = testInitiatedPayment(initiatedId, orderId);
        var expectedException = PaymentGatewayUnavailableException.ofPayment(orderId, new RuntimeException());
        when(paymentRepository.create(any())).thenReturn(initiatedPayment);
        when(paymentGateway.submitPayment(orderId, testAmount())).thenThrow(expectedException);

        assertThatThrownBy(() -> paymentService.submitPayment(orderId, testAmount()))
                .isSameAs(expectedException);

        verify(paymentRepository, times(1)).updateStatus(initiatedId, initiatedPayment.version(), PaymentStatus.FAILED);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitPayment_marksTheInitiatedPaymentFailedOnAPermanentGatewayError() {
        var orderId = 100L;
        var initiatedId = 1L;
        var initiatedPayment = testInitiatedPayment(initiatedId, orderId);
        var expectedException = PaymentGatewayRejectedException.ofPayment(orderId, new RuntimeException());
        when(paymentRepository.create(any())).thenReturn(initiatedPayment);
        when(paymentGateway.submitPayment(orderId, testAmount())).thenThrow(expectedException);

        assertThatThrownBy(() -> paymentService.submitPayment(orderId, testAmount()))
                .isSameAs(expectedException);

        verify(paymentRepository, times(1)).updateStatus(initiatedId, initiatedPayment.version(), PaymentStatus.FAILED);
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
        when(paymentRepository.updateStatus(id, payment.version(), status)).thenReturn(confirmedPayment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isEqualTo(confirmedPayment);
        verify(correlationTagger, times(1)).tag(KafkaUtils.formatKafkaId(orderId));
        verify(paymentKafkaProducer, times(1)).publishPaymentCompleted(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesNothingWhenStatusBecomesAttemptDeclined() {
        var id = 1L;
        var externalId = "pi_test";
        var status = PaymentStatus.ATTEMPT_DECLINED;
        var orderId = 100L;
        var payment = testPayment(id, orderId, PaymentStatus.PENDING);
        var declinedPayment = testPayment(id, orderId, status);
        when(paymentRepository.getByExternalId(externalId)).thenReturn(payment);
        when(paymentRepository.updateStatus(id, payment.version(), status)).thenReturn(declinedPayment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isEqualTo(declinedPayment);
        verify(correlationTagger, times(1)).tag(KafkaUtils.formatKafkaId(orderId));
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_writesAndPublishesNothingWhenThePaymentAlreadyHasTheStatus() {
        var externalId = "pi_test";
        var status = PaymentStatus.CONFIRMED;
        // e.g. Stripe redelivering payment_intent.succeeded for an already confirmed payment
        var orderId = 100L;
        var payment = testPayment(1L, orderId, status);
        when(paymentRepository.getByExternalId(externalId)).thenReturn(payment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isSameAs(payment);
        verify(correlationTagger, times(1)).tag(KafkaUtils.formatKafkaId(orderId));
        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }


    @Test
    void cancelPayment_cancelsThePaymentIntentThenThePaymentAndPublishesPaymentCancelled() {
        var paymentId = 1L;
        var orderId = 100L;
        var correlationId = "correlation-1";
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        var cancelled = testPayment(paymentId, orderId, PaymentStatus.CANCELLED);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.REQUESTED_BY_CUSTOMER))
                .thenReturn(PaymentIntentState.CANCELLED);
        when(paymentRepository.getById(paymentId)).thenReturn(testPayment(paymentId, orderId, PaymentStatus.PENDING));
        when(paymentRepository.updateStatus(paymentId, 0L, PaymentStatus.CANCELLED)).thenReturn(cancelled);

        var result = paymentService.cancelPayment(correlationId, orderId);

        assertThat(result).isSameAs(cancelled);
        verify(paymentGateway, times(1))
                .cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.REQUESTED_BY_CUSTOMER);
        verify(paymentRepository, times(1)).updateStatus(paymentId, 0L, PaymentStatus.CANCELLED);
        verify(paymentKafkaProducer, times(1)).publishPaymentCancelled(correlationId, orderId);
        verifyNoMoreInteractions(paymentGateway, paymentKafkaProducer);
    }

    @Test
    void cancelPayment_cancelsAPaymentThatNeverGotAPaymentIntentWithoutCallingTheGateway() {
        var paymentId = 1L;
        var orderId = 100L;
        var correlationId = "correlation-1";
        when(paymentRepository.getByOrderId(orderId)).thenReturn(testInitiatedPayment(paymentId, orderId));
        when(paymentRepository.getById(paymentId)).thenReturn(testInitiatedPayment(paymentId, orderId));

        paymentService.cancelPayment(correlationId, orderId);

        verify(paymentRepository, times(1)).updateStatus(paymentId, 0L, PaymentStatus.CANCELLED);
        verify(paymentKafkaProducer, times(1)).publishPaymentCancelled(correlationId, orderId);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void cancelPayment_returnsAPaymentThatCanNoLongerBePaidAsItIs() {
        var orderId = 100L;
        var payment = testPayment(1L, orderId, PaymentStatus.CANCELLED);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);

        var result = paymentService.cancelPayment("correlation-1", orderId);

        assertThat(result).isSameAs(payment);
        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentGateway, paymentKafkaProducer);
    }

    @Test
    void cancelPayment_rejectsAConfirmedPayment() {
        var orderId = 100L;
        when(paymentRepository.getByOrderId(orderId)).thenReturn(testPayment(1L, orderId, PaymentStatus.CONFIRMED));

        assertThatThrownBy(() -> paymentService.cancelPayment("correlation-1", orderId))
                .isInstanceOf(PaymentAlreadyConfirmedException.class);

        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentGateway, paymentKafkaProducer);
    }

    @Test
    void cancelPayment_confirmsAPaymentTheGatewaySaysIsPaidAndRejectsTheCancellation() {
        var paymentId = 1L;
        var orderId = 100L;
        var correlationId = "correlation-1";
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.REQUESTED_BY_CUSTOMER))
                .thenReturn(PaymentIntentState.SUCCEEDED);
        when(paymentRepository.getById(paymentId)).thenReturn(testPayment(paymentId, orderId, PaymentStatus.PENDING));

        assertThatThrownBy(() -> paymentService.cancelPayment(correlationId, orderId))
                .isInstanceOf(PaymentAlreadyConfirmedException.class);

        verify(paymentRepository, times(1)).updateStatus(paymentId, 0L, PaymentStatus.CONFIRMED);
        verify(paymentKafkaProducer, times(1)).publishPaymentCompleted(correlationId, orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void cancelPayment_rejectsThePaymentTheWebhookConfirmedMeanwhileWithoutConfirmingItAgain() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.REQUESTED_BY_CUSTOMER))
                .thenReturn(PaymentIntentState.SUCCEEDED);
        when(paymentRepository.getById(paymentId)).thenReturn(testPayment(paymentId, orderId, PaymentStatus.CONFIRMED));

        assertThatThrownBy(() -> paymentService.cancelPayment("correlation-1", orderId))
                .isInstanceOf(PaymentAlreadyConfirmedException.class);

        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void cancelPayment_reportsAPaymentTheGatewayIsStillProcessing() {
        var orderId = 100L;
        var payment = testPayment(1L, orderId, PaymentStatus.PENDING);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.REQUESTED_BY_CUSTOMER))
                .thenReturn(PaymentIntentState.PROCESSING);

        assertThatThrownBy(() -> paymentService.cancelPayment("correlation-1", orderId))
                .isInstanceOf(PaymentProcessingException.class);

        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void cancelPayment_returnsThePaymentTheSweepExpiredMeanwhileAsItIs() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        var expired = testPayment(paymentId, orderId, PaymentStatus.EXPIRED);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.REQUESTED_BY_CUSTOMER))
                .thenReturn(PaymentIntentState.CANCELLED);
        when(paymentRepository.getById(paymentId)).thenReturn(expired);

        var result = paymentService.cancelPayment("correlation-1", orderId);

        assertThat(result).isSameAs(expired);
        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void expireOverduePayments_cancelsThePaymentIntentThenExpiresThePaymentAndPublishesPaymentExpired() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        when(paymentRepository.findOverdue()).thenReturn(List.of(payment));
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.EXPIRED))
                .thenReturn(PaymentIntentState.CANCELLED);
        when(paymentRepository.getById(paymentId)).thenReturn(testPayment(paymentId, orderId, PaymentStatus.PENDING));

        paymentService.expireOverduePayments();

        verify(paymentGateway, times(1)).cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.EXPIRED);
        verify(paymentRepository, times(1)).updateStatus(paymentId, 0L, PaymentStatus.EXPIRED);
        verify(paymentKafkaProducer, times(1)).publishPaymentExpired(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
        verifyNoInteractions(correlationTagger);
    }

    @Test
    void expireOverduePayments_expiresAPaymentThatNeverGotAPaymentIntentWithoutCallingTheGatewayOrPublishing() {
        var paymentId = 1L;
        var orderId = 100L;
        when(paymentRepository.findOverdue()).thenReturn(List.of(testInitiatedPayment(paymentId, orderId)));
        when(paymentRepository.getById(paymentId)).thenReturn(testInitiatedPayment(paymentId, orderId));

        paymentService.expireOverduePayments();

        verify(paymentRepository, times(1)).updateStatus(paymentId, 0L, PaymentStatus.EXPIRED);
        verifyNoInteractions(paymentGateway, paymentKafkaProducer);
    }

    @Test
    void expireOverduePayments_confirmsAPaymentTheCustomerPaidAtTheLastMoment() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        when(paymentRepository.findOverdue()).thenReturn(List.of(payment));
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.EXPIRED))
                .thenReturn(PaymentIntentState.SUCCEEDED);
        when(paymentRepository.getById(paymentId)).thenReturn(testPayment(paymentId, orderId, PaymentStatus.PENDING));

        paymentService.expireOverduePayments();

        verify(paymentRepository, times(1)).updateStatus(paymentId, 0L, PaymentStatus.CONFIRMED);
        verify(paymentKafkaProducer, times(1)).publishPaymentCompleted(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void expireOverduePayments_leavesAPaymentTheGatewayIsStillProcessingForTheNextRun() {
        var orderId = 100L;
        var payment = testPayment(1L, orderId, PaymentStatus.PENDING);
        when(paymentRepository.findOverdue()).thenReturn(List.of(payment));
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.EXPIRED))
                .thenReturn(PaymentIntentState.PROCESSING);

        paymentService.expireOverduePayments();

        verify(paymentRepository, times(0)).getById(anyLong());
        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void expireOverduePayments_leavesAPaymentClosedMeanwhileAsItIs() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        when(paymentRepository.findOverdue()).thenReturn(List.of(payment));
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.EXPIRED))
                .thenReturn(PaymentIntentState.CANCELLED);
        when(paymentRepository.getById(paymentId)).thenReturn(testPayment(paymentId, orderId, PaymentStatus.CANCELLED));

        paymentService.expireOverduePayments();

        verify(paymentRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void expireOverduePayments_goesOnWithTheNextPaymentWhenOneFails() {
        var failingOrderId = 100L;
        var failing = testPayment(1L, failingOrderId, PaymentStatus.PENDING);
        var paymentId = 2L;
        var orderId = 200L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.PENDING);
        when(paymentRepository.findOverdue()).thenReturn(List.of(failing, payment));
        when(paymentGateway.cancelPayment(failingOrderId, failing.externalId(), PaymentCancellationReason.EXPIRED))
                .thenThrow(PaymentGatewayUnavailableException.ofPayment(failingOrderId, new RuntimeException("down")));
        when(paymentGateway.cancelPayment(orderId, payment.externalId(), PaymentCancellationReason.EXPIRED))
                .thenReturn(PaymentIntentState.CANCELLED);
        when(paymentRepository.getById(paymentId)).thenReturn(testPayment(paymentId, orderId, PaymentStatus.PENDING));

        paymentService.expireOverduePayments();

        verify(paymentRepository, times(1)).updateStatus(paymentId, 0L, PaymentStatus.EXPIRED);
        verify(paymentKafkaProducer, times(1)).publishPaymentExpired(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }
}
