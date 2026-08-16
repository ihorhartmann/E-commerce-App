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
        return Payment.createExisting(id, orderId, "pi_test", testAmount(), status, List.of(), now, now);
    }

    @Test
    void getByOrderId_returnsThePaymentFromTheRepository() {
        var orderId = 100L;
        var payment = testPayment(1L, orderId, PaymentStatus.COMPLETED);
        when(paymentRepository.getByOrderId(orderId)).thenReturn(payment);

        var result = paymentService.getByOrderId(orderId);

        assertThat(result).isEqualTo(payment);
    }

    @Test
    void submitPayment_createsAPendingPaymentFromTheGatewayResult() {
        var orderId = 100L;
        var externalId = "pi_new";
        var status = PaymentStatus.PENDING;
        when(paymentGateway.submitPayment(orderId, testAmount()))
                .thenReturn(new SubmitPaymentResult(externalId, "secret_123"));
        var createdPayment = testPayment(1L, orderId, status);
        when(paymentRepository.create(any())).thenReturn(createdPayment);

        var result = paymentService.submitPayment(orderId, testAmount());

        assertThat(result).isEqualTo(new PaymentIntentResult(createdPayment, "secret_123"));

        var captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).create(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(orderId);
        assertThat(captor.getValue().externalId()).isEqualTo(externalId);
        assertThat(captor.getValue().status()).isEqualTo(status);
    }

    @Test
    void updateStatusByExternalId_publishesPaymentCompletedWhenStatusBecomesCompleted() {
        var id = 1L;
        var orderId = 100L;
        var externalId = "pi_test";
        var status = PaymentStatus.COMPLETED;
        var payment = testPayment(id, orderId, PaymentStatus.PENDING);
        var completedPayment = testPayment(id, orderId, status);
        when(paymentRepository.getByExternalId(externalId)).thenReturn(payment);
        when(paymentRepository.updateStatus(id, status)).thenReturn(completedPayment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isEqualTo(completedPayment);
        verify(paymentKafkaProducer).publishPaymentCompleted(orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesPaymentFailedWhenStatusBecomesFailed() {
        var id = 1L;
        var orderId = 100L;
        var externalId = "pi_test";
        var status = PaymentStatus.FAILED;
        var payment = testPayment(id, orderId, PaymentStatus.PENDING);
        var failedPayment = testPayment(id, orderId, status);
        when(paymentRepository.getByExternalId(externalId)).thenReturn(payment);
        when(paymentRepository.updateStatus(id, status)).thenReturn(failedPayment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isEqualTo(failedPayment);
        verify(paymentKafkaProducer).publishPaymentFailed(orderId);
        verifyNoMoreInteractions(paymentKafkaProducer);
    }

    @Test
    void updateStatusByExternalId_publishesNothingWhenStatusIsPending() {
        var id = 1L;
        var externalId = "pi_test";
        var status = PaymentStatus.PENDING;
        var payment = testPayment(id, 100L, status);
        when(paymentRepository.getByExternalId(externalId)).thenReturn(payment);
        when(paymentRepository.updateStatus(id, status)).thenReturn(payment);

        var result = paymentService.updateStatusByExternalId(externalId, status);

        assertThat(result).isEqualTo(payment);
        verifyNoInteractions(paymentKafkaProducer);
    }
}
