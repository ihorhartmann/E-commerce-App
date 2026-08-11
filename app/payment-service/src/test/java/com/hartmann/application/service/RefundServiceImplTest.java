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
        return new Payment(id, orderId, "pi_test", money("100.00"), status, refunds, now, now);
    }

    private Refund testRefund(long id, long paymentId, RefundStatus status, String amount) {
        var now = Instant.now();
        return new Refund(id, paymentId, "re_test", money(amount), status, now, now);
    }

    @Test
    void getByOrderId_returnsRefundsForThePaymentTiedToTheOrder() {
        var payment = testPayment(1L, 100L, PaymentStatus.COMPLETED, List.of());
        var refunds = List.of(testRefund(1L, 1L, RefundStatus.PENDING, "20.00"));
        when(paymentRepository.getByOrderId(100L)).thenReturn(payment);
        when(refundRepository.findByPaymentId(1L)).thenReturn(refunds);

        var result = refundService.getByOrderId(100L);

        assertThat(result).isEqualTo(refunds);
    }

    @Test
    void submitRefund_createsARefundViaTheGatewayResult() {
        var payment = testPayment(1L, 100L, PaymentStatus.COMPLETED, List.of());
        when(paymentRepository.getById(1L)).thenReturn(payment);
        when(refundRepository.findByPaymentId(1L)).thenReturn(List.of());
        when(paymentGateway.submitRefund("pi_test", money("40.00")))
                .thenReturn(new SubmitRefundResult("re_new", RefundStatus.PENDING));
        var savedRefund = testRefund(1L, 1L, RefundStatus.PENDING, "40.00");
        when(refundRepository.create(any())).thenReturn(savedRefund);

        var result = refundService.submitRefund(1L, money("40.00"));

        assertThat(result).isEqualTo(savedRefund);

        var captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).create(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(1L);
        assertThat(captor.getValue().externalId()).isEqualTo("re_new");
        assertThat(captor.getValue().status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void updateStatusByExternalId_publishesRefundCompletedWhenStatusBecomesSucceeded() {
        var refund = testRefund(1L, 1L, RefundStatus.PENDING, "40.00");
        var succeededRefund = testRefund(1L, 1L, RefundStatus.SUCCEEDED, "40.00");
        var payment = testPayment(1L, 100L, PaymentStatus.COMPLETED, List.of());
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, RefundStatus.SUCCEEDED)).thenReturn(succeededRefund);
        when(paymentRepository.getById(1L)).thenReturn(payment);

        refundService.updateStatusByExternalId("re_test", RefundStatus.SUCCEEDED);

        verify(paymentKafkaProducer).publishRefundCompleted(100L, money("40.00"));
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesRefundFailedWhenStatusBecomesFailed() {
        var refund = testRefund(1L, 1L, RefundStatus.PENDING, "40.00");
        var failedRefund = testRefund(1L, 1L, RefundStatus.FAILED, "40.00");
        var payment = testPayment(1L, 100L, PaymentStatus.COMPLETED, List.of());
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, RefundStatus.FAILED)).thenReturn(failedRefund);
        when(paymentRepository.getById(1L)).thenReturn(payment);

        refundService.updateStatusByExternalId("re_test", RefundStatus.FAILED);

        verify(paymentKafkaProducer).publishRefundFailed(100L);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesNothingWhenStatusIsPending() {
        var refund = testRefund(1L, 1L, RefundStatus.PENDING, "40.00");
        when(refundRepository.getByExternalId("re_test")).thenReturn(refund);
        when(refundRepository.updateStatus(1L, RefundStatus.PENDING)).thenReturn(refund);

        refundService.updateStatusByExternalId("re_test", RefundStatus.PENDING);

        verifyNoInteractions(paymentKafkaProducer);
    }
}
