package com.hartmann.application.service;

import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private RefundServiceImpl refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundServiceImpl(refundRepository, paymentRepository, paymentGateway, paymentKafkaProducer);
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }

    private Payment testPayment(long id, long orderId, PaymentStatus status, List<Refund> refunds) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", money("100.00"), status, refunds, now, now);
    }

    private Refund testRefund(long id, long paymentId, RefundStatus status, String amount) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, "re_test", money(amount), status, now, now);
    }

    @Test
    void getByOrderId_returnsRefundsForThePaymentTiedToTheOrder() {
        var paymentId = 1L;
        var orderId = 100L;
        var payment = testPayment(paymentId, orderId, PaymentStatus.COMPLETED, List.of());
        var refunds = List.of(testRefund(1L, paymentId, RefundStatus.PENDING, "20.00"));
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(refunds);

        var result = refundService.getByOrderId(orderId);

        assertThat(result).isEqualTo(refunds);
    }

    @Test
    void submitRefund_createsARefundViaTheGatewayResult() {
        var paymentId = 1L;
        var amount = "40.00";

        var payment = testPayment(paymentId, 100L, PaymentStatus.COMPLETED, List.of());
        when(paymentRepository.getById(paymentId)).thenReturn(payment);
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of());
        when(paymentGateway.submitRefund("pi_test", money(amount)))
                .thenReturn(new SubmitRefundResult("re_new", RefundStatus.PENDING));
        var savedRefund = testRefund(1L, paymentId, RefundStatus.PENDING, amount);
        when(refundRepository.create(any())).thenReturn(savedRefund);

        var result = refundService.submitRefund(paymentId, money(amount));

        assertThat(result).isEqualTo(savedRefund);

        var captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).create(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(paymentId);
        assertThat(captor.getValue().externalId()).isEqualTo("re_new");
        assertThat(captor.getValue().status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void updateStatusByExternalId_publishesRefundCompletedWhenStatusBecomesSucceeded() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = "40.00";
        var status = RefundStatus.SUCCEEDED;

        var refund = testRefund(1L, paymentId, RefundStatus.PENDING, amount);
        var succeededRefund = testRefund(1L, paymentId, status, amount);
        var payment = testPayment(paymentId, orderId, PaymentStatus.COMPLETED, List.of());
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, status)).thenReturn(succeededRefund);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);

        refundService.updateStatusByExternalId("re_test", status);

        verify(paymentKafkaProducer).publishRefundCompleted(orderId, money(amount));
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
        var payment = testPayment(paymentId, orderId, PaymentStatus.COMPLETED, List.of());
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, status)).thenReturn(failedRefund);
        when(paymentRepository.getById(paymentId)).thenReturn(payment);

        refundService.updateStatusByExternalId("re_test", status);

        verify(paymentKafkaProducer).publishRefundFailed(orderId);
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
    }
}
