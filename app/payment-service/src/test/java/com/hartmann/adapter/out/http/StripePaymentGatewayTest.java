package com.hartmann.adapter.out.http;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class StripePaymentGatewayTest {

    private StripeClient stripeClient;
    private StripePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        stripeClient = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        gateway = new StripePaymentGateway(stripeClient);
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }

    // com.stripe.model.Refund is imported for the gateway's responses, so the domain refund is fully qualified here.
    private com.hartmann.domain.model.Refund testRefund(long id, UUID idempotencyKey, String amount) {
        return com.hartmann.domain.model.Refund.createExisting(
                id, 1L, idempotencyKey, null, money(amount), RefundStatus.INITIATED, Instant.now(), null);
    }

    // --- submitPayment ---

    @Test
    void submitPayment_createsAPaymentIntentAndReturnsItsIdAndClientSecret() throws Exception {
        var orderId = 42L;
        var paymentIntentId = "pi_test";
        var clientSecret = "pi_test_secret_abc";
        var intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(paymentIntentId);
        when(intent.getClientSecret()).thenReturn(clientSecret);
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(intent);

        var result = gateway.submitPayment(orderId, money("79.99"));

        assertThat(result).isEqualTo(new SubmitPaymentResult(paymentIntentId, clientSecret));
    }

    @Test
    void submitPayment_sendsTheAmountInCentsCurrencyAndOrderIdMetadata() throws Exception {
        var orderId = 42L;
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mock(PaymentIntent.class));

        gateway.submitPayment(orderId, money("79.99"));

        var paramsCaptor = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(stripeClient.v1().paymentIntents(), times(1)).create(paramsCaptor.capture(), any(RequestOptions.class));
        var params = paramsCaptor.getValue();
        assertThat(params.getAmount()).isEqualTo(7999L);
        assertThat(params.getCurrency()).isEqualTo("usd");
        assertThat(params.getMetadata()).containsEntry("orderId", String.valueOf(orderId));
    }

    @Test
    void submitPayment_setsAnIdempotencyKeyScopedToTheOrder() throws Exception {
        var orderId = 42L;
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mock(PaymentIntent.class));

        gateway.submitPayment(orderId, money("79.99"));

        var optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().paymentIntents(), times(1)).create(any(PaymentIntentCreateParams.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getIdempotencyKey()).isEqualTo("order-" + orderId);
    }

    @Test
    void submitPayment_throwsPaymentGatewayUnavailableExceptionWhenStripeIsTemporarilyUnavailable() throws Exception {
        var orderId = 42L;
        var cause = new ApiConnectionException("connection failed");
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitPayment(orderId, money("79.99")))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    void submitPayment_throwsPaymentGatewayExceptionWhenStripeRejectsTheRequest() throws Exception {
        var orderId = 42L;
        var cause = new InvalidRequestException("Amount must be at least $0.50 usd", "amount", null, null, null, null);
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitPayment(orderId, money("0.10")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasCause(cause);
    }

    // --- submitRefund ---

    @Test
    void submitRefund_createsARefundAndReturnsItsIdAndMappedStatus() throws Exception {
        var refundId = "re_test";
        var stripeRefund = mock(Refund.class);
        when(stripeRefund.getId()).thenReturn(refundId);
        when(stripeRefund.getStatus()).thenReturn("succeeded");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stripeRefund);

        var result = gateway.submitRefund("pi_test", testRefund(1L, UUID.randomUUID(), "40.00"));

        assertThat(result).isEqualTo(new SubmitRefundResult(refundId, RefundStatus.SUCCEEDED));
    }

    @Test
    void submitRefund_sendsTheAmountInCentsThePaymentIntentIdAndRefundIdMetadata() throws Exception {
        var paymentExternalId = "pi_test";
        var refundId = 7L;
        var stripeRefund = mock(Refund.class);
        when(stripeRefund.getStatus()).thenReturn("pending");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stripeRefund);

        gateway.submitRefund(paymentExternalId, testRefund(refundId, UUID.randomUUID(), "40.00"));

        var paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(stripeClient.v1().refunds(), times(1)).create(paramsCaptor.capture(), any(RequestOptions.class));
        var params = paramsCaptor.getValue();
        assertThat(params.getAmount()).isEqualTo(4000L);
        assertThat(params.getPaymentIntent()).isEqualTo(paymentExternalId);
        assertThat(params.getMetadata()).isEqualTo(Map.of("refundId", String.valueOf(refundId)));
    }

    @Test
    void submitRefund_setsAnIdempotencyKeyScopedToTheRefundsIdempotencyKey() throws Exception {
        var idempotencyKey = UUID.randomUUID();
        var stripeRefund = mock(Refund.class);
        when(stripeRefund.getStatus()).thenReturn("pending");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stripeRefund);

        gateway.submitRefund("pi_test", testRefund(1L, idempotencyKey, "40.00"));

        var optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().refunds(), times(1)).create(any(RefundCreateParams.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getIdempotencyKey()).isEqualTo("refund-" + idempotencyKey);
    }

    @Test
    void submitRefund_throwsPaymentGatewayUnavailableExceptionWhenStripeIsTemporarilyUnavailable() throws Exception {
        var cause = new ApiConnectionException("connection failed");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitRefund("pi_test", testRefund(1L, UUID.randomUUID(), "40.00")))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    void submitRefund_throwsPaymentGatewayExceptionWhenStripeRejectsTheRequest() throws Exception {
        var cause = new InvalidRequestException(
                "Refund amount exceeds the amount available to refund", "amount", null, null, null, null);
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitRefund("pi_test", testRefund(1L, UUID.randomUUID(), "999.00")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasCause(cause);
    }
}
