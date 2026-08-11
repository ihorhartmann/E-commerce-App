package com.hartmann.application.service;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentServiceImplTest {

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
        return new Payment(id, orderId, "pi_test", testAmount(), status, List.of(), now, now);
    }

    @Test
    void getByOrderId_returnsThePaymentFromTheRepository() {
        var payment = testPayment(1L, 100L, PaymentStatus.COMPLETED);
        when(paymentRepository.getByOrderId(100L)).thenReturn(payment);

        var result = paymentService.getByOrderId(100L);

        assertThat(result).isEqualTo(payment);
    }

    @Test
    void submitPayment_createsAPendingPaymentFromTheGatewayResult() {
        when(paymentGateway.submitPayment(100L, testAmount()))
                .thenReturn(new SubmitPaymentResult("pi_new", "secret_123"));
        var createdPayment = testPayment(1L, 100L, PaymentStatus.PENDING);
        when(paymentRepository.create(any())).thenReturn(createdPayment);

        var result = paymentService.submitPayment(100L, testAmount());

        assertThat(result).isEqualTo(new PaymentIntentResult(createdPayment, "secret_123"));

        var captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).create(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(100L);
        assertThat(captor.getValue().externalId()).isEqualTo("pi_new");
        assertThat(captor.getValue().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void updateStatusByExternalId_publishesPaymentCompletedWhenStatusBecomesCompleted() {
        var payment = testPayment(1L, 100L, PaymentStatus.PENDING);
        var completedPayment = testPayment(1L, 100L, PaymentStatus.COMPLETED);
        when(paymentRepository.getByExternalId("pi_test")).thenReturn(payment);
        when(paymentRepository.updateStatus(1L, PaymentStatus.COMPLETED)).thenReturn(completedPayment);

        var result = paymentService.updateStatusByExternalId("pi_test", PaymentStatus.COMPLETED);

        assertThat(result).isEqualTo(completedPayment);
        verify(paymentKafkaProducer).publishPaymentCompleted(100L);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesPaymentFailedWhenStatusBecomesFailed() {
        var payment = testPayment(1L, 100L, PaymentStatus.PENDING);
        var failedPayment = testPayment(1L, 100L, PaymentStatus.FAILED);
        when(paymentRepository.getByExternalId("pi_test")).thenReturn(payment);
        when(paymentRepository.updateStatus(1L, PaymentStatus.FAILED)).thenReturn(failedPayment);

        var result = paymentService.updateStatusByExternalId("pi_test", PaymentStatus.FAILED);

        assertThat(result).isEqualTo(failedPayment);
        verify(paymentKafkaProducer).publishPaymentFailed(100L);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesNothingWhenStatusIsPending() {
        var payment = testPayment(1L, 100L, PaymentStatus.PENDING);
        when(paymentRepository.getByExternalId("pi_test")).thenReturn(payment);
        when(paymentRepository.updateStatus(1L, PaymentStatus.PENDING)).thenReturn(payment);

        var result = paymentService.updateStatusByExternalId("pi_test", PaymentStatus.PENDING);

        assertThat(result).isEqualTo(payment);
        verifyNoInteractions(paymentKafkaProducer);
    }
}
