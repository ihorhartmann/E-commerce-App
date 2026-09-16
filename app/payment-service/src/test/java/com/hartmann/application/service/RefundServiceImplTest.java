package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.idempotency.ProcessedKafkaMessageStore;
import com.hartmann.application.port.out.idempotency.dto.KafkaMessageMetadata;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
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
    private ProcessedKafkaMessageStore processedKafkaMessageStore;
    @Mock
    private PlatformTransactionManager transactionManager;

    private RefundServiceImpl refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundServiceImpl(
                refundRepository, paymentRepository, paymentGateway, paymentKafkaProducer,
                processedKafkaMessageStore, transactionManager
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }

    private Payment testPayment(long id, long orderId, PaymentStatus status, List<Refund> refunds) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", money("100.00"), status, refunds, now, now, now.plusSeconds(900));
    }

    private Refund testRefund(long id, long paymentId, RefundStatus status, String amount) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, "re_test", money(amount), status, now, now);
    }

    private Refund testInitiatedRefund(long id, long paymentId, String amount) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, null, money(amount), RefundStatus.INITIATED, now, null);
    }

    @Test
    void getByOrderId_returnsRefundsForThePaymentTiedToTheOrder() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var refunds = List.of(testRefund(1L, paymentId, RefundStatus.PENDING, "20.00"));
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

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, amount);
        var pendingRefund = testRefund(initiatedId, paymentId, RefundStatus.PENDING, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", money(amount)))
                .thenReturn(new SubmitRefundResult("re_new", RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, "re_new", RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(null, paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);

        var captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository, times(1)).create(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(paymentId);
        assertThat(captor.getValue().status()).isEqualTo(RefundStatus.INITIATED);
        verify(refundRepository, times(1)).markPending(initiatedId, "re_new", RefundStatus.PENDING);
        verifyNoInteractions(paymentKafkaProducer);
        verifyNoInteractions(processedKafkaMessageStore);
    }

    @Test
    void submitRefund_publishesRefundCompletedWhenStripeSucceedsTheRefundSynchronously() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, amount);
        var succeededRefund = testRefund(initiatedId, paymentId, RefundStatus.SUCCEEDED, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", money(amount)))
                .thenReturn(new SubmitRefundResult("re_new", RefundStatus.SUCCEEDED));
        when(refundRepository.markPending(initiatedId, "re_new", RefundStatus.SUCCEEDED)).thenReturn(succeededRefund);

        var result = refundService.submitRefund(null, paymentId, money(amount));

        assertThat(result).isEqualTo(succeededRefund);
        verify(paymentKafkaProducer, times(1)).publishRefundCompleted(KafkaUtils.formatKafkaId(orderId), orderId, money(amount));
        verifyNoMoreInteractions(paymentKafkaProducer);
        verifyNoInteractions(processedKafkaMessageStore);
    }

    @Test
    void submitRefund_publishesRefundCompletedWithThePropagatedCorrelationIdWhenAMessageIsPresentAndStripeResolvesSynchronously() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var message = new KafkaMessageMetadata("msg-1", "corr-1", "RefundRequested");

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, amount);
        var succeededRefund = testRefund(initiatedId, paymentId, RefundStatus.SUCCEEDED, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(processedKafkaMessageStore.markProcessed(message)).thenReturn(false);
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", money(amount)))
                .thenReturn(new SubmitRefundResult("re_new", RefundStatus.SUCCEEDED));
        when(refundRepository.markPending(initiatedId, "re_new", RefundStatus.SUCCEEDED)).thenReturn(succeededRefund);

        var result = refundService.submitRefund(message, paymentId, money(amount));

        assertThat(result).isEqualTo(succeededRefund);
        verify(paymentKafkaProducer, times(1)).publishRefundCompleted(message.correlationId(), orderId, money(amount));
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void submitRefund_marksTheInitiatedRefundFailedAndRethrowsWhenTheGatewayCallFails() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, amount);
        var expectedException = new PaymentGatewayUnavailableException("Stripe unavailable", new RuntimeException());
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", money(amount))).thenThrow(expectedException);

        assertThatThrownBy(() -> refundService.submitRefund(null, paymentId, money(amount)))
                .isSameAs(expectedException);

        verify(refundRepository, times(1)).updateStatus(initiatedId, RefundStatus.FAILED);
        verifyNoInteractions(paymentKafkaProducer);
        verifyNoInteractions(processedKafkaMessageStore);
    }

    @Test
    void submitRefund_marksTheInitiatedRefundFailedOnAPermanentGatewayError() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, amount);
        var expectedException = new PaymentGatewayException("invalid request", new RuntimeException());
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", money(amount))).thenThrow(expectedException);

        assertThatThrownBy(() -> refundService.submitRefund(null, paymentId, money(amount)))
                .isSameAs(expectedException);

        verify(refundRepository, times(1)).updateStatus(initiatedId, RefundStatus.FAILED);
        verifyNoInteractions(paymentKafkaProducer);
        verifyNoInteractions(processedKafkaMessageStore);
    }

    @Test
    void submitRefund_marksTheKafkaMessageProcessedThenCreatesAnInitiatedRefundWhenMessageIsPresent() {
        var paymentId = 1L;
        var orderId = 100L;
        var initiatedId = 1L;
        var amount = "40.00";
        var message = new KafkaMessageMetadata("msg-1", "corr-1", "RefundRequested");

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        var initiatedRefund = testInitiatedRefund(initiatedId, paymentId, amount);
        var pendingRefund = testRefund(initiatedId, paymentId, RefundStatus.PENDING, amount);
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(refundRepository.create(any())).thenReturn(initiatedRefund);
        when(paymentGateway.submitRefund("pi_test", money(amount)))
                .thenReturn(new SubmitRefundResult("re_new", RefundStatus.PENDING));
        when(refundRepository.markPending(initiatedId, "re_new", RefundStatus.PENDING)).thenReturn(pendingRefund);

        var result = refundService.submitRefund(message, paymentId, money(amount));

        assertThat(result).isEqualTo(pendingRefund);
        verify(processedKafkaMessageStore, times(1)).markProcessed(message);
        verify(refundRepository, times(1)).create(any());
    }

    @Test
    void submitRefund_returnsNullWithoutCallingTheGatewayWhenTheKafkaMessageWasAlreadyProcessed() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = "40.00";
        var message = new KafkaMessageMetadata("msg-1", "corr-1", "RefundRequested");

        var payment = testPayment(paymentId, orderId, PaymentStatus.CONFIRMED, List.of());
        when(paymentRepository.getByIdWithRefunds(paymentId)).thenReturn(payment);
        when(processedKafkaMessageStore.markProcessed(message)).thenReturn(true);

        var result = refundService.submitRefund(message, paymentId, money(amount));

        assertThat(result).isNull();
        verify(processedKafkaMessageStore, times(1)).markProcessed(message);
        verify(refundRepository, times(0)).create(any());
        verifyNoInteractions(paymentGateway);
        verifyNoInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesRefundCompletedWhenStatusBecomesSucceeded() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = "40.00";
        var status = RefundStatus.SUCCEEDED;

        var refund = testRefund(1L, paymentId, RefundStatus.PENDING, amount);
        var succeededRefund = testRefund(1L, paymentId, status, amount);
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

        var refund = testRefund(1L, paymentId, RefundStatus.PENDING, amount);
        var failedRefund = testRefund(1L, paymentId, status, amount);
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

        var refund = testRefund(id, 1L, status, "40.00");
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
        var refund = testRefund(id, 1L, status, amount);
        when(refundRepository.getByExternalId(externalId)).thenReturn(refund);
        when(refundRepository.updateStatus(id, status)).thenReturn(refund);

        refundService.updateStatusByExternalId(externalId, status);

        verifyNoInteractions(paymentKafkaProducer);
        verify(paymentRepository, times(0)).getById(anyLong());
    }
}
