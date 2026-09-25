package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.application.port.out.repository.exception.DuplicateIdempotencyKeyException;
import com.hartmann.application.service.exception.IdempotencyKeyReusedException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
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
import java.util.Optional;
import java.util.UUID;

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
class RefundServiceImplTest {

    @Mock
    private RefundRepository refundRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private PaymentKafkaProducer paymentKafkaProducer;
    @Mock
    private PlatformTransactionManager transactionManager;

    private RefundServiceImpl refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundServiceImpl(
                refundRepository, paymentRepository, paymentGateway, paymentKafkaProducer, transactionManager
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }

    private Payment testPayment(long id, long orderId, PaymentStatus status, List<Refund> refunds) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", money("100.00"), status, refunds, now, now, now.plusSeconds(900));
    }

    private Refund testRefund(long id, long paymentId, UUID idempotencyKey, RefundStatus status, String amount) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, idempotencyKey, "re_test", money(amount), status, now, now);
    }

    private Refund testInitiatedRefund(long id, long paymentId, UUID idempotencyKey, String amount) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, idempotencyKey, null, money(amount), RefundStatus.INITIATED, now, null);
    }

    @Test
    void getByOrderId_returnsRefundsForThePaymentTiedToTheOrder() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var refunds = List.of(testRefund(1L, paymentId, UUID.randomUUID(), RefundStatus.PENDING, "20.00"));
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(refunds);

        var result = refundService.getByOrderId(orderId);

        assertThat(result).isEqualTo(refunds);
    }

    @Test
    void submitRefund_createsAnInitiatedRefundThenMarksItSubmittedWhenStripeLeavesItPending() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var paymentExternalId = "pi_test";
        var externalId = "re_new";

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var pendingRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund(paymentExternalId, initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, externalId, RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(idempotencyKey, null, paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);

        var captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository, times(1)).findByIdempotencyKey(idempotencyKey);
        verify(refundRepository, times(1)).create(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(paymentId);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(captor.getValue().status()).isEqualTo(RefundStatus.INITIATED);
        verify(paymentGateway, times(1)).submitRefund(paymentExternalId, initiatedRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, externalId, RefundStatus.PENDING);
        verify(refundRepository, times(0)).updateStatus(anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_publishesRefundCompletedWhenStripeSucceedsTheRefundSynchronously() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var externalId = "re_new";

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var succeededRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.SUCCEEDED, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.SUCCEEDED));
        when(refundRepository.markPending(initiatedId, externalId, RefundStatus.SUCCEEDED)).thenReturn(succeededRefund);

        var result = refundService.submitRefund(idempotencyKey, null, paymentId, money(amount));

        assertThat(result).isEqualTo(succeededRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, externalId, RefundStatus.SUCCEEDED);
        verify(paymentKafkaProducer, times(1)).publishRefundCompleted(KafkaUtils.formatKafkaId(orderId), orderId, money(amount));
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_publishesRefundFailedWhenStripeFailsTheRefundSynchronously() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var externalId = "re_new";

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var failedRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.FAILED, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.FAILED));
        when(refundRepository.markPending(initiatedId, externalId, RefundStatus.FAILED)).thenReturn(failedRefund);

        var result = refundService.submitRefund(idempotencyKey, null, paymentId, money(amount));

        assertThat(result).isEqualTo(failedRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, externalId, RefundStatus.FAILED);
        verify(paymentKafkaProducer, times(1)).publishRefundFailed(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_publishesWithThePropagatedCorrelationIdWhenOneIsGiven() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var correlationId = "corr-1";
        var externalId = "re_new";

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var succeededRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.SUCCEEDED, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.SUCCEEDED));
        when(refundRepository.markPending(initiatedId, externalId, RefundStatus.SUCCEEDED)).thenReturn(succeededRefund);

        var result = refundService.submitRefund(idempotencyKey, correlationId, paymentId, money(amount));

        assertThat(result).isEqualTo(succeededRefund);
        verify(paymentKafkaProducer, times(1)).publishRefundCompleted(correlationId, orderId, money(amount));
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_resubmitsTheInitiatedRefundItsKeyAlreadyCreatedInsteadOfCreatingAnother() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var paymentExternalId = "pi_test";
        var externalId = "re_new";

        // e.g. a previous attempt committed the INITIATED row, then found the gateway unavailable.
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of(initiatedRefund));
        var pendingRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(initiatedRefund));
        when(paymentGateway.submitRefund(paymentExternalId, initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, externalId, RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(idempotencyKey, null, paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);
        verify(refundRepository, times(0)).create(any());
        verify(paymentGateway, times(1)).submitRefund(paymentExternalId, initiatedRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, externalId, RefundStatus.PENDING);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_resumesTheRefundAConcurrentRequestWithTheSameKeyCreatedFirst() {
        var paymentId = 1L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var paymentExternalId = "pi_test";
        var externalId = "re_new";

        // The concurrent request's row didn't exist yet at the first find, but did by the insert.
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var payment = testPayment(paymentId, 100L, PaymentStatus.CONFIRMED, List.of());
        var pendingRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty(), Optional.of(initiatedRefund));
        when(refundRepository.create(any())).thenThrow(new DuplicateIdempotencyKeyException("duplicate"));
        when(paymentGateway.submitRefund(paymentExternalId, initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, externalId, RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(idempotencyKey, null, paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);
        verify(refundRepository, times(2)).findByIdempotencyKey(idempotencyKey);
        verify(refundRepository, times(1)).create(any());
        verify(paymentGateway, times(1)).submitRefund(paymentExternalId, initiatedRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, externalId, RefundStatus.PENDING);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_returnsAnAlreadySubmittedRefundAsIsWithoutCallingTheGateway() {
        var paymentId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();

        var pendingRefund = testRefund(1L, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        var payment = testPayment(paymentId, 100L, PaymentStatus.CONFIRMED, List.of(pendingRefund));
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(pendingRefund));

        var result = refundService.submitRefund(idempotencyKey, null, paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);
        verify(refundRepository, times(0)).create(any());
        verify(refundRepository, times(0)).markPending(anyLong(), any(), any());
        verify(refundRepository, times(0)).updateStatus(anyLong(), any());
        verifyNoInteractions(paymentGateway);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_rejectsAnIdempotencyKeyThatAlreadyBelongsToADifferentRefund() {
        var paymentId = 1L;
        var idempotencyKey = UUID.randomUUID();

        var existingRefund = testInitiatedRefund(1L, paymentId, idempotencyKey, "40.00");
        var payment = testPayment(paymentId, 100L, PaymentStatus.CONFIRMED, List.of(existingRefund));
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingRefund));

        assertThatThrownBy(() -> refundService.submitRefund(idempotencyKey, null, paymentId, money("30.00")))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verify(refundRepository, times(0)).create(any());
        verify(refundRepository, times(0)).updateStatus(anyLong(), any());
        verifyNoInteractions(paymentGateway);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_leavesTheRefundInitiatedAndRethrowsWhenTheGatewayIsUnavailable() {
        var paymentId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();

        var payment = testPayment(paymentId, 100L, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(1L, paymentId, idempotencyKey, amount);
        var expectedException = new PaymentGatewayUnavailableException("Stripe unavailable", new RuntimeException());
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", initiatedRefund)).thenThrow(expectedException);

        assertThatThrownBy(() -> refundService.submitRefund(idempotencyKey, null, paymentId, money(amount)))
                .isSameAs(expectedException);

        verify(refundRepository, times(0)).updateStatus(anyLong(), any());
        verify(refundRepository, times(0)).markPending(anyLong(), any(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_marksTheRefundFailedPublishesRefundFailedAndRethrowsOnAPermanentGatewayError() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var expectedException = new PaymentGatewayException("invalid request", new RuntimeException());
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", initiatedRefund)).thenThrow(expectedException);

        assertThatThrownBy(() -> refundService.submitRefund(idempotencyKey, null, paymentId, money(amount)))
                .isSameAs(expectedException);

        verify(refundRepository, times(1)).updateStatus(initiatedId, RefundStatus.FAILED);
        verify(refundRepository, times(0)).markPending(anyLong(), any(), any());
        verify(paymentKafkaProducer, times(1)).publishRefundFailed(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesRefundCompletedWhenStatusBecomesSucceeded() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = "40.00";
        var status = RefundStatus.SUCCEEDED;
        var idempotencyKey = UUID.randomUUID();

        var refund = testRefund(1L, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        var succeededRefund = testRefund(1L, paymentId, idempotencyKey, status, amount);
        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, status)).thenReturn(succeededRefund);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);

        refundService.updateStatusByExternalId("re_test", status);

        verify(paymentKafkaProducer, times(1)).publishRefundCompleted(KafkaUtils.formatKafkaId(orderId), orderId, money(amount));
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesRefundFailedWhenStatusBecomesFailed() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = "40.00";
        var status = RefundStatus.FAILED;
        var idempotencyKey = UUID.randomUUID();

        var refund = testRefund(1L, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        var failedRefund = testRefund(1L, paymentId, idempotencyKey, status, amount);
        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, status)).thenReturn(failedRefund);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);

        refundService.updateStatusByExternalId("re_test", status);

        verify(paymentKafkaProducer, times(1)).publishRefundFailed(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesNothingWhenStatusIsPending() {
        var id = 1L;
        var externalId = "re_test";
        var status = RefundStatus.PENDING;

        var refund = testRefund(id, 1L, UUID.randomUUID(), status, "40.00");
        when(refundRepository.getByExternalId(externalId)).thenReturn(refund);
        when(refundRepository.updateStatus(id, status)).thenReturn(refund);

        refundService.updateStatusByExternalId(externalId, status);

        verifyNoInteractions(paymentKafkaProducer);
        verify(paymentRepository, times(0)).getById(anyLong());
    }

    @Test
    void updateStatusByExternalId_publishesNothingWhenTheRefundWasAlreadySucceeded() {
        var id = 1L;
        var externalId = "re_test";
        var status = RefundStatus.SUCCEEDED;
        var amount = "40.00";

        // e.g. submitRefund already published synchronously, and this call is a later
        // refund.updated webhook reporting the same already-terminal status.
        var refund = testRefund(id, 1L, UUID.randomUUID(), status, amount);
        when(refundRepository.getByExternalId(externalId)).thenReturn(refund);
        when(refundRepository.updateStatus(id, status)).thenReturn(refund);

        refundService.updateStatusByExternalId(externalId, status);

        verifyNoInteractions(paymentKafkaProducer);
        verify(paymentRepository, times(0)).getById(anyLong());
    }
}
