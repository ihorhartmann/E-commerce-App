package com.hartmann.application.service;

import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.http.exception.PaymentGatewayRejectedException;
import com.hartmann.application.port.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.application.port.out.repository.exception.DuplicateIdempotencyKeyException;
import com.hartmann.application.service.exception.ConflictingRefundNotFoundException;
import com.hartmann.application.service.exception.RefundIdempotencyKeyReusedException;
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

    private Payment testPayment(long id, long orderId, String refundedAmount) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", money("100.00"), PaymentStatus.CONFIRMED,
                money(refundedAmount), 0L, now, now, now.plusSeconds(900));
    }

    private Refund testRefund(long id, long paymentId, UUID idempotencyKey, RefundStatus status, String amount) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, idempotencyKey, "re_test", money(amount), status, 0L, now, now);
    }

    private Refund testInitiatedRefund(long id, long paymentId, UUID idempotencyKey, String amount) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, idempotencyKey, null, money(amount), RefundStatus.INITIATED, 0L, now, null);
    }

    @Test
    void getByOrderId_returnsRefundsForThePaymentTiedToTheOrder() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, "0.00");
        var refunds = List.of(testRefund(1L, paymentId, UUID.randomUUID(), RefundStatus.PENDING, "20.00"));
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(refunds);

        var result = refundService.getByOrderId(orderId);

        assertThat(result).isEqualTo(refunds);
    }

    @Test
    void submitRefund_createsAnInitiatedRefundReservingItsAmountThenMarksItSubmittedWhenStripeLeavesItPending() {
        var paymentId = 1L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var paymentExternalId = "pi_test";
        var externalId = "re_new";

        var payment = testPayment(paymentId, 100L, "10.00");
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var pendingRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentRepository.updateRefundedAmount(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentGateway.submitRefund(paymentExternalId, initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(idempotencyKey, "corr-1", paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);

        var refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository, times(1)).findByIdempotencyKey(idempotencyKey);
        verify(refundRepository, times(1)).create(refundCaptor.capture());
        assertThat(refundCaptor.getValue().paymentId()).isEqualTo(paymentId);
        assertThat(refundCaptor.getValue().idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(refundCaptor.getValue().status()).isEqualTo(RefundStatus.INITIATED);
        var paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(1)).updateRefundedAmount(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().refundedAmount()).isEqualTo(money("50.00"));
        verify(paymentGateway, times(1)).submitRefund(paymentExternalId, initiatedRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.PENDING);
        verify(refundRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_publishesRefundCompletedWhenStripeSucceedsTheRefundSynchronously() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var correlationId = "corr-1";
        var externalId = "re_new";

        var payment = testPayment(paymentId, orderId, "0.00");
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var succeededRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.SUCCEEDED, amount);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentRepository.updateRefundedAmount(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentGateway.submitRefund("pi_test", initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.SUCCEEDED));
        when(refundRepository.markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.SUCCEEDED)).thenReturn(succeededRefund);

        var result = refundService.submitRefund(idempotencyKey, correlationId, paymentId, money(amount));

        assertThat(result).isEqualTo(succeededRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.SUCCEEDED);
        verify(paymentRepository, times(1)).updateRefundedAmount(any());
        verify(paymentKafkaProducer, times(1)).publishRefundCompleted(correlationId, orderId, money(amount));
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_releasesTheRefundedAmountAndPublishesRefundFailedWhenStripeFailsTheRefundSynchronously() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var correlationId = "corr-1";
        var externalId = "re_new";

        var payment = testPayment(paymentId, orderId, "0.00");
        var paymentWithRefund = testPayment(paymentId, orderId, amount);
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var failedRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.FAILED, amount);
        // loaded at the start, when creating the refund, and again when releasing it
        when(paymentRepository.getById(paymentId)).thenReturn(payment, payment, paymentWithRefund);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentRepository.updateRefundedAmount(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentGateway.submitRefund("pi_test", initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.FAILED));
        when(refundRepository.markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.FAILED)).thenReturn(failedRefund);

        var result = refundService.submitRefund(idempotencyKey, correlationId, paymentId, money(amount));

        assertThat(result).isEqualTo(failedRefund);
        var paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).updateRefundedAmount(paymentCaptor.capture());
        assertThat(paymentCaptor.getAllValues().get(0).refundedAmount()).isEqualTo(money(amount));
        assertThat(paymentCaptor.getAllValues().get(1).refundedAmount()).isEqualTo(money("0.00"));
        verify(refundRepository, times(1)).markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.FAILED);
        verify(paymentKafkaProducer, times(1)).publishRefundFailed(correlationId, orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_resubmitsTheInitiatedRefundItsKeyAlreadyCreatedWithoutReservingItsAmountAgain() {
        var paymentId = 1L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var paymentExternalId = "pi_test";
        var externalId = "re_new";

        // e.g. a previous attempt committed the INITIATED row, then found the gateway unavailable.
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var payment = testPayment(paymentId, 100L, amount);
        var pendingRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(initiatedRefund));
        when(paymentGateway.submitRefund(paymentExternalId, initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(idempotencyKey, "corr-1", paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);
        verify(refundRepository, times(0)).create(any());
        verify(paymentRepository, times(0)).updateRefundedAmount(any());
        verify(paymentGateway, times(1)).submitRefund(paymentExternalId, initiatedRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.PENDING);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_resumesTheRefundAConcurrentRequestWithTheSameKeyCreatedFirstWithoutReservingItsAmount() {
        var paymentId = 1L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var paymentExternalId = "pi_test";
        var externalId = "re_new";

        // The concurrent request's row didn't exist yet at the first find, but did by the insert.
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var payment = testPayment(paymentId, 100L, "0.00");
        var pendingRefund = testRefund(initiatedId, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty(), Optional.of(initiatedRefund));
        when(refundRepository.create(any())).thenThrow(new DuplicateIdempotencyKeyException(idempotencyKey));
        when(paymentGateway.submitRefund(paymentExternalId, initiatedRefund))
                .thenReturn(new SubmitRefundResult(externalId, RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(idempotencyKey, "corr-1", paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);
        verify(refundRepository, times(2)).findByIdempotencyKey(idempotencyKey);
        verify(refundRepository, times(1)).create(any());
        verify(paymentRepository, times(0)).updateRefundedAmount(any());
        verify(paymentGateway, times(1)).submitRefund(paymentExternalId, initiatedRefund);
        verify(refundRepository, times(1)).markPending(initiatedId, initiatedRefund.version(), externalId, RefundStatus.PENDING);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_reportsARefundThatConflictedOnInsertButCantBeFoundAsAnInternalFailure() {
        var paymentId = 1L;
        var idempotencyKey = UUID.randomUUID();

        var payment = testPayment(paymentId, 100L, "0.00");
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        var exception = new DuplicateIdempotencyKeyException(idempotencyKey);
        when(refundRepository.create(any())).thenThrow(exception);

        assertThatThrownBy(() -> refundService.submitRefund(idempotencyKey, "corr-1", paymentId, money("40.00")))
                .isInstanceOf(ConflictingRefundNotFoundException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(exception));
        verify(refundRepository, times(2)).findByIdempotencyKey(idempotencyKey);
        verify(refundRepository, times(1)).create(any());
        verify(paymentRepository, times(0)).updateRefundedAmount(any());
        verifyNoInteractions(paymentGateway, paymentKafkaProducer);
    }

    @Test
    void submitRefund_returnsAnAlreadySubmittedRefundAsIsWithoutCallingTheGateway() {
        var paymentId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();

        var pendingRefund = testRefund(1L, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        var payment = testPayment(paymentId, 100L, amount);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(pendingRefund));

        var result = refundService.submitRefund(idempotencyKey, "corr-1", paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);
        verify(refundRepository, times(0)).create(any());
        verify(refundRepository, times(0)).markPending(anyLong(), anyLong(), any(), any());
        verify(refundRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verify(paymentRepository, times(0)).updateRefundedAmount(any());
        verifyNoInteractions(paymentGateway);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_rejectsAnIdempotencyKeyThatAlreadyBelongsToADifferentRefund() {
        var paymentId = 1L;
        var idempotencyKey = UUID.randomUUID();

        var existingRefund = testInitiatedRefund(1L, paymentId, idempotencyKey, "40.00");
        var payment = testPayment(paymentId, 100L, "40.00");
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingRefund));

        assertThatThrownBy(() -> refundService.submitRefund(idempotencyKey, "corr-1", paymentId, money("30.00")))
                .isInstanceOf(RefundIdempotencyKeyReusedException.class);

        verify(refundRepository, times(0)).create(any());
        verify(refundRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verify(paymentRepository, times(0)).updateRefundedAmount(any());
        verifyNoInteractions(paymentGateway);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_leavesTheRefundInitiatedAndItsAmountReservedAndRethrowsWhenTheGatewayIsUnavailable() {
        var paymentId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();

        var initiatedId = 1L;
        var payment = testPayment(paymentId, 100L, "0.00");
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var expectedException = PaymentGatewayUnavailableException.ofRefund("pi_test", initiatedId, new RuntimeException());
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentRepository.updateRefundedAmount(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentGateway.submitRefund("pi_test", initiatedRefund)).thenThrow(expectedException);

        assertThatThrownBy(() -> refundService.submitRefund(idempotencyKey, "corr-1", paymentId, money(amount)))
                .isSameAs(expectedException);

        verify(paymentRepository, times(1)).updateRefundedAmount(any());
        verify(refundRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verify(refundRepository, times(0)).markPending(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_marksTheRefundFailedReleasesItsAmountPublishesRefundFailedAndRethrowsOnAPermanentGatewayError() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var correlationId = "corr-1";

        var payment = testPayment(paymentId, orderId, "0.00");
        var paymentWithRefund = testPayment(paymentId, orderId, amount);
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, idempotencyKey, amount);
        var expectedException = PaymentGatewayRejectedException.ofRefund("pi_test", initiatedId, new RuntimeException());
        // loaded at the start, when creating the refund, and again when releasing it
        when(paymentRepository.getById(paymentId)).thenReturn(payment, payment, paymentWithRefund);
        when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentRepository.updateRefundedAmount(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentGateway.submitRefund("pi_test", initiatedRefund)).thenThrow(expectedException);

        assertThatThrownBy(() -> refundService.submitRefund(idempotencyKey, correlationId, paymentId, money(amount)))
                .isSameAs(expectedException);

        verify(refundRepository, times(1)).updateStatus(initiatedId, initiatedRefund.version(), RefundStatus.FAILED);
        var paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).updateRefundedAmount(paymentCaptor.capture());
        assertThat(paymentCaptor.getAllValues().get(1).refundedAmount()).isEqualTo(money("0.00"));
        verify(refundRepository, times(0)).markPending(anyLong(), anyLong(), any(), any());
        verify(paymentKafkaProducer, times(1)).publishRefundFailed(correlationId, orderId);
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
        var payment = testPayment(paymentId, orderId, amount);
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, refund.version(), status)).thenReturn(succeededRefund);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);

        refundService.updateStatusByExternalId("re_test", status);

        verify(paymentRepository, times(0)).updateRefundedAmount(any());
        verify(paymentKafkaProducer, times(1)).publishRefundCompleted(KafkaUtils.formatKafkaId(orderId), orderId, money(amount));
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_releasesTheRefundedAmountAndPublishesRefundFailedWhenStatusBecomesFailed() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = "40.00";
        var status = RefundStatus.FAILED;
        var idempotencyKey = UUID.randomUUID();

        var refund = testRefund(1L, paymentId, idempotencyKey, RefundStatus.PENDING, amount);
        var failedRefund = testRefund(1L, paymentId, idempotencyKey, status, amount);
        var payment = testPayment(paymentId, orderId, amount);
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, refund.version(), status)).thenReturn(failedRefund);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(paymentRepository.updateRefundedAmount(any())).thenAnswer(invocation -> invocation.getArgument(0));

        refundService.updateStatusByExternalId("re_test", status);

        var paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(1)).updateRefundedAmount(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().refundedAmount()).isEqualTo(money("0.00"));
        verify(paymentKafkaProducer, times(1)).publishRefundFailed(KafkaUtils.formatKafkaId(orderId), orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_writesAndPublishesNothingWhenTheRefundAlreadyHasTheStatus() {
        var externalId = "re_test";
        var status = RefundStatus.SUCCEEDED;

        // e.g. submitRefund already published synchronously, and this call is a later
        // refund.updated webhook reporting the same already-terminal status.
        var refund = testRefund(1L, 1L, UUID.randomUUID(), status, "40.00");
        when(refundRepository.getByExternalId(externalId)).thenReturn(refund);

        var result = refundService.updateStatusByExternalId(externalId, status);

        assertThat(result).isSameAs(refund);
        verify(refundRepository, times(0)).updateStatus(anyLong(), anyLong(), any());
        verify(paymentRepository, times(0)).getById(anyLong());
        verify(paymentRepository, times(0)).updateRefundedAmount(any());
        verifyNoInteractions(paymentKafkaProducer);
    }
}
