package com.hartmann.adapter.in.http.webhook;

import com.hartmann.adapter.in.http.GlobalExceptionHandler;
import com.hartmann.adapter.in.http.exception.HttpErrorCode;
import com.hartmann.adapter.in.http.idempotency.HttpIdempotencyService;
import com.hartmann.adapter.in.http.webhook.exception.WebhookErrorCode;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.repository.exception.ConcurrentUpdateException;
import com.hartmann.application.port.out.repository.exception.RepositoryErrorCode;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.model.exception.PaymentTransitionNotAllowedException;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import com.hartmann.infrastructure.config.SecurityConfig;
import com.stripe.Stripe;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = StripeWebhookController.class,
        properties = {
                "oidc.roles-claim=realm_access.roles",
                "stripe.webhook-secret=" + StripeWebhookControllerTest.WEBHOOK_SECRET
        }
)
@Import(SecurityConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class StripeWebhookControllerTest {
    // only signs the test events, never checked by a test itself
    static final String WEBHOOK_SECRET = "whsec_test";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private RefundService refundService;

    // IdempotencyFilter is part of this slice, so it needs a bean here even though it skips the webhook.
    @MockitoBean
    private HttpIdempotencyService httpIdempotencyService;

    // SecurityConfig's real decoder would fetch Keycloak's keys; the webhook is authenticated by its signature instead
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /**
     * An event as Stripe sends it, with the API version stripe-java deserializes its data object for.
     */
    private static String event(String type, String dataObject) {
        return """
                {"id": "evt_test", "object": "event", "api_version": "%s", "type": "%s", "data": {"object": %s}}
                """.formatted(Stripe.API_VERSION, type, dataObject);
    }

    private static String paymentIntentEvent(String type, String paymentIntentId) {
        return event(type, """
                {"id": "%s", "object": "payment_intent"}""".formatted(paymentIntentId));
    }

    private static String refundEvent(String refundId, String stripeStatus) {
        return event("refund.updated", """
                {"id": "%s", "object": "refund", "status": "%s"}""".formatted(refundId, stripeStatus));
    }

    /**
     * Sends payload with the Stripe-Signature header Stripe computes for it, so it passes the signature check.
     */
    private ResultActions sendToWebhook(String payload) throws Exception {
        var timestamp = Instant.now().getEpochSecond();
        var signature = Webhook.Util.computeHmacSha256(WEBHOOK_SECRET, timestamp + "." + payload);
        return mockMvc.perform(post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=" + timestamp + ",v1=" + signature)
                .content(payload));
    }

    private static Payment testPayment(String externalId, PaymentStatus status) {
        var now = Instant.now();
        var amount = new Money(new BigDecimal("50.00"), Currency.USD);
        return Payment.createExisting(1L, 100L, externalId, amount, status, Money.zero(Currency.USD), 1L, now, now,
                now.plusSeconds(900));
    }

    private static Refund testRefund(String externalId, RefundStatus status) {
        var now = Instant.now();
        return Refund.createExisting(1L, 1L, UUID.randomUUID(), externalId, new Money(new BigDecimal("10.00"), Currency.USD),
                status, 1L, now, now);
    }

    @Test
    void handleWebhook_returns400WhenTheStripeSignatureHeaderIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(HttpErrorCode.MISSING_HEADER.code()))
                .andExpect(jsonPath("$.title").value(HttpErrorCode.MISSING_HEADER.title()));

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void handleWebhook_returns400WhenTheStripeSignatureIsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=invalid")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(WebhookErrorCode.INVALID_WEBHOOK_SIGNATURE.code()))
                .andExpect(jsonPath("$.title").value(WebhookErrorCode.INVALID_WEBHOOK_SIGNATURE.title()));

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void handleWebhook_confirmsThePaymentOfASucceededPaymentIntent() throws Exception {
        var paymentIntentId = "pi_succeeded";
        when(paymentService.updateStatusByExternalId(paymentIntentId, PaymentStatus.CONFIRMED))
                .thenReturn(testPayment(paymentIntentId, PaymentStatus.CONFIRMED));

        sendToWebhook(paymentIntentEvent("payment_intent.succeeded", paymentIntentId))
                .andExpect(status().isOk());

        verify(paymentService, times(1)).updateStatusByExternalId(paymentIntentId, PaymentStatus.CONFIRMED);
        verifyNoInteractions(refundService);
    }

    @Test
    void handleWebhook_declinesTheAttemptOfAFailedPaymentIntent() throws Exception {
        var paymentIntentId = "pi_failed";
        when(paymentService.updateStatusByExternalId(paymentIntentId, PaymentStatus.ATTEMPT_DECLINED))
                .thenReturn(testPayment(paymentIntentId, PaymentStatus.ATTEMPT_DECLINED));

        sendToWebhook(paymentIntentEvent("payment_intent.payment_failed", paymentIntentId))
                .andExpect(status().isOk());

        verify(paymentService, times(1)).updateStatusByExternalId(paymentIntentId, PaymentStatus.ATTEMPT_DECLINED);
        verifyNoInteractions(refundService);
    }

    @Test
    void handleWebhook_acknowledgesAPaymentIntentEventThePaymentRejectsPermanently() throws Exception {
        var paymentIntentId = "pi_expired";
        // e.g. a confirmation arriving after the payment already expired
        when(paymentService.updateStatusByExternalId(paymentIntentId, PaymentStatus.CONFIRMED))
                .thenThrow(new PaymentTransitionNotAllowedException(1L, "confirm", PaymentStatus.EXPIRED));

        sendToWebhook(paymentIntentEvent("payment_intent.succeeded", paymentIntentId))
                .andExpect(status().isOk());

        verify(paymentService, times(1)).updateStatusByExternalId(paymentIntentId, PaymentStatus.CONFIRMED);
    }

    @Test
    void handleWebhook_returnsARetryable503WhenThePaymentCantBeUpdatedNow() throws Exception {
        var paymentIntentId = "pi_busy";
        when(paymentService.updateStatusByExternalId(paymentIntentId, PaymentStatus.CONFIRMED))
                .thenThrow(ConcurrentUpdateException.ofPayment(1L, 1L));

        sendToWebhook(paymentIntentEvent("payment_intent.succeeded", paymentIntentId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, String.valueOf(GlobalExceptionHandler.RETRY_AFTER_SECONDS)))
                .andExpect(jsonPath("$.code").value(RepositoryErrorCode.CONCURRENT_UPDATE.code()))
                .andExpect(jsonPath("$.retryable").value(true));

        verify(paymentService, times(1)).updateStatusByExternalId(paymentIntentId, PaymentStatus.CONFIRMED);
    }

    @Test
    void handleWebhook_appliesTheStatusOfAnUpdatedRefund() throws Exception {
        var refundId = "re_succeeded";
        when(refundService.updateStatusByExternalId(refundId, RefundStatus.SUCCEEDED))
                .thenReturn(testRefund(refundId, RefundStatus.SUCCEEDED));

        sendToWebhook(refundEvent(refundId, "succeeded"))
                .andExpect(status().isOk());

        verify(refundService, times(1)).updateStatusByExternalId(refundId, RefundStatus.SUCCEEDED);
        verifyNoInteractions(paymentService);
    }

    @Test
    void handleWebhook_acknowledgesARefundEventWithAnUnknownStatusWithoutUpdatingTheRefund() throws Exception {
        sendToWebhook(refundEvent("re_unknown", "some_new_status"))
                .andExpect(status().isOk());

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void handleWebhook_returnsARetryable503WhenTheRefundCantBeUpdatedNow() throws Exception {
        var refundId = "re_busy";
        when(refundService.updateStatusByExternalId(refundId, RefundStatus.FAILED))
                .thenThrow(ConcurrentUpdateException.ofRefund(1L, 1L));

        sendToWebhook(refundEvent(refundId, "failed"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(RepositoryErrorCode.CONCURRENT_UPDATE.code()))
                .andExpect(jsonPath("$.retryable").value(true));

        verify(refundService, times(1)).updateStatusByExternalId(refundId, RefundStatus.FAILED);
    }

    @Test
    void handleWebhook_acknowledgesAnUnhandledEventTypeWithoutUpdatingAnything() throws Exception {
        sendToWebhook(event("customer.created", "{\"id\": \"cus_test\", \"object\": \"customer\"}"))
                .andExpect(status().isOk());

        verifyNoInteractions(paymentService, refundService);
    }
}
