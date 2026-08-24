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
        var paymentExternalId = "pi_test";
        var refundId = "re_test";
        var refund = mock(Refund.class);
        when(refund.getId()).thenReturn(refundId);
        when(refund.getStatus()).thenReturn("succeeded");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class))).thenReturn(refund);

        var result = gateway.submitRefund(paymentExternalId, money("40.00"));

        assertThat(result).isEqualTo(new SubmitRefundResult(refundId, RefundStatus.SUCCEEDED));
    }

    @Test
    void submitRefund_sendsTheAmountInCentsAndThePaymentIntentId() throws Exception {
        var paymentExternalId = "pi_test";
        var refund = mock(Refund.class);
        when(refund.getStatus()).thenReturn("pending");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class))).thenReturn(refund);

        gateway.submitRefund(paymentExternalId, money("40.00"));

        var paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(stripeClient.v1().refunds(), times(1)).create(paramsCaptor.capture());
        var params = paramsCaptor.getValue();
        assertThat(params.getAmount()).isEqualTo(4000L);
        assertThat(params.getPaymentIntent()).isEqualTo(paymentExternalId);
    }

    @Test
    void submitRefund_throwsPaymentGatewayUnavailableExceptionWhenStripeIsTemporarilyUnavailable() throws Exception {
        var paymentExternalId = "pi_test";
        var cause = new ApiConnectionException("connection failed");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitRefund(paymentExternalId, money("40.00")))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    void submitRefund_throwsPaymentGatewayExceptionWhenStripeRejectsTheRequest() throws Exception {
        var paymentExternalId = "pi_test";
        var cause = new InvalidRequestException(
                "Refund amount exceeds the amount available to refund", "amount", null, null, null, null);
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitRefund(paymentExternalId, money("999.00")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasCause(cause);
    }
}
