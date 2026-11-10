package com.hartmann.adapter.out.http;

import com.hartmann.adapter.out.http.exception.PaymentGatewayAuthenticationException;
import com.hartmann.adapter.out.http.exception.UnexpectedPaymentIntentStatusException;
import com.hartmann.application.port.out.http.dto.PaymentCancellationReason;
import com.hartmann.application.port.out.http.dto.PaymentIntentState;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.http.exception.PaymentGatewayRejectedException;
import com.hartmann.application.port.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.micrometer.observation.ObservationRegistry;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        gateway = new StripePaymentGateway(stripeClient, ObservationRegistry.NOOP);
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }

    // com.stripe.model.Refund is imported for the gateway's responses, so the domain refund is fully qualified here.
    private com.hartmann.domain.model.Refund testRefund(long id, UUID idempotencyKey, String amount) {
        return com.hartmann.domain.model.Refund.createExisting(
                id, 1L, idempotencyKey, null, money(amount), RefundStatus.INITIATED, 0L, Instant.now(), null);
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
    void submitPayment_allowsCardPaymentsOnly() throws Exception {
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mock(PaymentIntent.class));

        gateway.submitPayment(42L, money("79.99"));

        var paramsCaptor = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(stripeClient.v1().paymentIntents(), times(1)).create(paramsCaptor.capture(), any(RequestOptions.class));
        var params = paramsCaptor.getValue();
        assertThat(params.getPaymentMethodTypes()).containsExactly("card");
        // with automatic payment methods Stripe would offer whatever the account enables (MB WAY, Satispay, ...)
        assertThat(params.getAutomaticPaymentMethods()).isNull();
    }

    @Test
    void submitPayment_setsAnIdempotencyKeyScopedToTheOrder() throws Exception {
        var orderId = 42L;
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mock(PaymentIntent.class));

        gateway.submitPayment(orderId, money("79.99"));

        var optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().paymentIntents(), times(1)).create(any(PaymentIntentCreateParams.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getIdempotencyKey()).isEqualTo("placeOrder-" + orderId);
    }

    @Test
    void submitPayment_letsTheStripeClientRetryTransientNetworkFailures() throws Exception {
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mock(PaymentIntent.class));

        gateway.submitPayment(42L, money("79.99"));

        var optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().paymentIntents(), times(1)).create(any(PaymentIntentCreateParams.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getMaxNetworkRetries()).isEqualTo(2);
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
                .isInstanceOf(PaymentGatewayRejectedException.class)
                .hasCause(cause);
    }

    @Test
    void submitPayment_throwsPaymentGatewayAuthenticationExceptionWhenStripeRefusesTheApiKey() throws Exception {
        var orderId = 42L;
        var cause = new AuthenticationException("Invalid API Key provided", null, null, 401);
        when(stripeClient.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitPayment(orderId, money("79.99")))
                .isInstanceOf(PaymentGatewayAuthenticationException.class)
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
    void submitRefund_leavesNetworkRetriesToTheStripeClientDefault() throws Exception {
        var stripeRefund = mock(Refund.class);
        when(stripeRefund.getStatus()).thenReturn("pending");
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stripeRefund);

        gateway.submitRefund("pi_test", testRefund(1L, UUID.randomUUID(), "40.00"));

        var optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().refunds(), times(1)).create(any(RefundCreateParams.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getMaxNetworkRetries()).isNull();
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
                .isInstanceOf(PaymentGatewayRejectedException.class)
                .hasCause(cause);
    }

    @Test
    void submitRefund_throwsPaymentGatewayAuthenticationExceptionWhenTheApiKeyLacksThePermission() throws Exception {
        var cause = new PermissionException("The provided key does not have access to refunds", null, null, 403);
        when(stripeClient.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.submitRefund("pi_test", testRefund(1L, UUID.randomUUID(), "40.00")))
                .isInstanceOf(PaymentGatewayAuthenticationException.class)
                .hasCause(cause);
    }

    // --- cancelPayment ---

    private static PaymentIntent intentWithStatus(String status) {
        var intent = mock(PaymentIntent.class);
        when(intent.getStatus()).thenReturn(status);
        return intent;
    }

    private static InvalidRequestException unexpectedStateError() {
        return new InvalidRequestException("This PaymentIntent's status is canceled", null, null,
                "payment_intent_unexpected_state", 400, null);
    }

    @Test
    void cancelPayment_cancelsThePaymentIntentAndReportsItCancelled() throws Exception {
        var paymentIntentId = "pi_test";
        var intent = intentWithStatus("canceled");
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenReturn(intent);

        var state = gateway.cancelPayment(42L, paymentIntentId, PaymentCancellationReason.REQUESTED_BY_CUSTOMER);

        assertThat(state).isEqualTo(PaymentIntentState.CANCELLED);
        verify(stripeClient.v1().paymentIntents(), times(1))
                .cancel(eq(paymentIntentId), any(PaymentIntentCancelParams.class),
                        any(RequestOptions.class));
        verify(stripeClient.v1().paymentIntents(), never()).retrieve(any(String.class));
    }

    @Test
    void cancelPayment_sendsTheCustomersReasonUnderAKeyOfTheCustomersCancellation() throws Exception {
        var orderId = 42L;
        var intent = intentWithStatus("canceled");
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenReturn(intent);

        gateway.cancelPayment(orderId, "pi_test", PaymentCancellationReason.REQUESTED_BY_CUSTOMER);

        var paramsCaptor = ArgumentCaptor.forClass(PaymentIntentCancelParams.class);
        var optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().paymentIntents(), times(1))
                .cancel(any(String.class), paramsCaptor.capture(), optionsCaptor.capture());
        assertThat(paramsCaptor.getValue().getCancellationReason())
                .isEqualTo(PaymentIntentCancelParams.CancellationReason.REQUESTED_BY_CUSTOMER);
        assertThat(optionsCaptor.getValue().getIdempotencyKey()).isEqualTo("cancelOrder-" + orderId);
    }

    @Test
    void cancelPayment_sendsTheAbandonedReasonUnderAKeyOfTheExpiration() throws Exception {
        var orderId = 42L;
        var intent = intentWithStatus("canceled");
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenReturn(intent);

        gateway.cancelPayment(orderId, "pi_test", PaymentCancellationReason.EXPIRED);

        var paramsCaptor = ArgumentCaptor.forClass(PaymentIntentCancelParams.class);
        var optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().paymentIntents(), times(1))
                .cancel(any(String.class), paramsCaptor.capture(), optionsCaptor.capture());
        assertThat(paramsCaptor.getValue().getCancellationReason())
                .isEqualTo(PaymentIntentCancelParams.CancellationReason.ABANDONED);
        assertThat(optionsCaptor.getValue().getIdempotencyKey()).isEqualTo("expirePayment-" + orderId);
    }

    @Test
    void cancelPayment_reportsAPaymentIntentStripeRefusesToCancelAsItIsNow() throws Exception {
        var paymentIntentId = "pi_test";
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(unexpectedStateError());
        var paid = intentWithStatus("succeeded");
        when(stripeClient.v1().paymentIntents().retrieve(paymentIntentId)).thenReturn(paid);

        var state = gateway.cancelPayment(42L, paymentIntentId, PaymentCancellationReason.REQUESTED_BY_CUSTOMER);

        assertThat(state).isEqualTo(PaymentIntentState.SUCCEEDED);
        verify(stripeClient.v1().paymentIntents(), times(1)).retrieve(paymentIntentId);
    }

    @Test
    void cancelPayment_reportsAnAlreadyCancelledPaymentIntentCancelled() throws Exception {
        var paymentIntentId = "pi_test";
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(unexpectedStateError());
        var cancelled = intentWithStatus("canceled");
        when(stripeClient.v1().paymentIntents().retrieve(paymentIntentId)).thenReturn(cancelled);

        var state = gateway.cancelPayment(42L, paymentIntentId, PaymentCancellationReason.EXPIRED);

        assertThat(state).isEqualTo(PaymentIntentState.CANCELLED);
    }

    @Test
    void cancelPayment_reportsAPaymentIntentStillProcessing() throws Exception {
        var intent = intentWithStatus("processing");
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenReturn(intent);

        var state = gateway.cancelPayment(42L, "pi_test", PaymentCancellationReason.EXPIRED);

        assertThat(state).isEqualTo(PaymentIntentState.PROCESSING);
    }

    @Test
    void cancelPayment_throwsForAPaymentIntentInAStatusACancellationCantLeaveItIn() throws Exception {
        var intent = intentWithStatus("requires_capture");
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenReturn(intent);

        assertThatThrownBy(() -> gateway.cancelPayment(42L, "pi_test", PaymentCancellationReason.EXPIRED))
                .isInstanceOf(UnexpectedPaymentIntentStatusException.class);
    }

    @Test
    void cancelPayment_throwsPaymentGatewayExceptionWhenStripeRejectsTheRequestForAnotherReason() throws Exception {
        var cause = new InvalidRequestException("No such payment_intent", "intent", null, "resource_missing", 404, null);
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.cancelPayment(42L, "pi_test", PaymentCancellationReason.REQUESTED_BY_CUSTOMER))
                .isInstanceOf(PaymentGatewayRejectedException.class)
                .hasCause(cause);
        verify(stripeClient.v1().paymentIntents(), never()).retrieve(any(String.class));
    }

    @Test
    void cancelPayment_throwsPaymentGatewayUnavailableExceptionWhenStripeIsTemporarilyUnavailable() throws Exception {
        var cause = new ApiConnectionException("connection failed");
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.cancelPayment(42L, "pi_test", PaymentCancellationReason.REQUESTED_BY_CUSTOMER))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    void cancelPayment_throwsPaymentGatewayAuthenticationExceptionWhenStripeRefusesTheApiKey() throws Exception {
        var cause = new AuthenticationException("Invalid API Key provided", null, null, 401);
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.cancelPayment(42L, "pi_test", PaymentCancellationReason.EXPIRED))
                .isInstanceOf(PaymentGatewayAuthenticationException.class)
                .hasCause(cause);
    }

    @Test
    void cancelPayment_throwsPaymentGatewayUnavailableExceptionWhenReadingTheRefusedPaymentIntentFails() throws Exception {
        var refusal = unexpectedStateError();
        when(stripeClient.v1().paymentIntents().cancel(any(String.class), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(refusal);
        var cause = new ApiConnectionException("connection failed");
        when(stripeClient.v1().paymentIntents().retrieve(any(String.class))).thenThrow(cause);

        assertThatThrownBy(() -> gateway.cancelPayment(42L, "pi_test", PaymentCancellationReason.EXPIRED))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasCause(cause);
        assertThat(cause.getSuppressed()).containsExactly(refusal);
    }
}
