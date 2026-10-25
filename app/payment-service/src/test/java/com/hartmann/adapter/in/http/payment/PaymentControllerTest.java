package com.hartmann.adapter.in.http.payment;

import com.hartmann.adapter.in.http.GlobalExceptionHandler;
import com.hartmann.adapter.in.http.exception.HttpErrorCode;
import com.hartmann.adapter.in.http.idempotency.HttpIdempotencyService;
import com.hartmann.adapter.in.http.idempotency.dto.RequestAttempt;
import com.hartmann.adapter.in.http.idempotency.dto.RequestResult;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.application.port.out.http.exception.GatewayErrorCode;
import com.hartmann.application.port.out.http.exception.PaymentGatewayRejectedException;
import com.hartmann.application.port.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.out.idempotency.dto.HttpRequestRecord;
import com.hartmann.application.port.out.repository.exception.PaymentNotFoundException;
import com.hartmann.application.port.out.repository.exception.RepositoryErrorCode;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import com.hartmann.domain.value.exception.ValueErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import com.hartmann.infrastructure.config.SecurityConfig;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PaymentController.class, properties = "oidc.roles-claim=realm_access.roles")
@Import(SecurityConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    // IdempotencyFilter runs ahead of every controller in this slice too, so it needs a bean here
    // regardless of whether a given test cares about idempotency itself.
    @MockitoBean
    private HttpIdempotencyService httpIdempotencyService;

    @BeforeEach
    void stubIdempotencyStart() {
        when(httpIdempotencyService.claim(any(), any(), any(Duration.class))).thenReturn(RequestAttempt.claimed());
    }

    // SecurityConfig's real decoder would fetch Keycloak's keys; the tests authenticate with jwt() instead
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor jwtWithRole(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static JwtRequestPostProcessor adminJwt() {
        return jwtWithRole("ADMIN");
    }

    private static JwtRequestPostProcessor orderServiceJwt() {
        return jwtWithRole("PAYMENT_SUBMIT");
    }

    private Money testAmount() {
        return new Money(new BigDecimal("50.00"), Currency.USD);
    }

    private Payment testPayment(long id, long orderId, PaymentStatus status) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", testAmount(), status, Money.zero(Currency.USD), 0L, now, now, now.plusSeconds(900));
    }

    private String submitPaymentRequestJson(long orderId) {
        return """
                {"orderId": %d, "amount": 50.00, "currency": "USD"}
                """.formatted(orderId);
    }

    @Test
    void getByOrderId_returns200WithThePayment() throws Exception {
        var orderId = 100L;
        var payment = testPayment(1L, orderId, PaymentStatus.CONFIRMED);
        when(paymentService.getByOrderId(orderId)).thenReturn(payment);

        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.id()))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value(payment.status().toString()));

        verify(paymentService, times(1)).getByOrderId(orderId);
    }

    @Test
    void getByOrderId_returns404WhenThePaymentIsNotFound() throws Exception {
        var orderId = 999L;
        when(paymentService.getByOrderId(orderId)).thenThrow(PaymentNotFoundException.withOrderId(orderId));

        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId).with(adminJwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(RepositoryErrorCode.PAYMENT_NOT_FOUND.code()))
                .andExpect(jsonPath("$.title").value(RepositoryErrorCode.PAYMENT_NOT_FOUND.title()))
                .andExpect(jsonPath("$.type").value("urn:ecommerce:error:" + RepositoryErrorCode.PAYMENT_NOT_FOUND.code()))
                .andExpect(jsonPath("$.instance").value("/api/v1/payments/" + orderId))
                .andExpect(jsonPath("$.retryable").value(false));

        verify(paymentService, times(1)).getByOrderId(orderId);
    }

    @Test
    void submitPayment_returns201WithTheCreatedPaymentIntent() throws Exception {
        var orderId = 100L;
        var clientSecret = "secret_123";
        var payment = testPayment(1L, orderId, PaymentStatus.PENDING);
        when(paymentService.submitPayment(eq(orderId), eq(testAmount())))
                .thenReturn(new PaymentIntentResult(payment, clientSecret));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1")
                        .content(submitPaymentRequestJson(orderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(payment.id()))
                .andExpect(jsonPath("$.clientSecret").value(clientSecret));

        verify(paymentService, times(1)).submitPayment(eq(orderId), eq(testAmount()));
        verify(httpIdempotencyService, times(1)).complete(eq("key-1"), eq(HttpStatus.CREATED.value()), any());
        verify(httpIdempotencyService, never()).release(any());
    }

    @Test
    void submitPayment_returns400WithoutCallingTheServiceWhenTheCurrencyIsUnsupported() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-2")
                        .content("""
                                {"orderId": 100, "amount": 50.00, "currency": "XYZ"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ValueErrorCode.UNSUPPORTED_CURRENCY.code()))
                .andExpect(jsonPath("$.title").value(ValueErrorCode.UNSUPPORTED_CURRENCY.title()));

        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_returns409AndRecordsTheResponseWhenTheGatewayRejectsThePayment() throws Exception {
        var orderId = 100L;
        var idempotencyKey = "key-rejected";
        when(paymentService.submitPayment(anyLong(), eq(testAmount())))
                .thenThrow(PaymentGatewayRejectedException.ofPayment(orderId, new RuntimeException("card declined")));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(submitPaymentRequestJson(orderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(GatewayErrorCode.PAYMENT_GATEWAY_REJECTED.code()))
                .andExpect(jsonPath("$.retryable").value(false));

        verify(paymentService, times(1)).submitPayment(anyLong(), eq(testAmount()));
        verify(httpIdempotencyService, times(1)).complete(eq(idempotencyKey), eq(HttpStatus.CONFLICT.value()), any());
        verify(httpIdempotencyService, never()).release(any());
    }

    @Test
    void submitPayment_returnsARetryable503AndReleasesTheKeyWhenTheGatewayIsUnavailable() throws Exception {
        var orderId = 100L;
        var idempotencyKey = "key-unavailable";
        when(paymentService.submitPayment(anyLong(), eq(testAmount())))
                .thenThrow(PaymentGatewayUnavailableException.ofPayment(orderId, new RuntimeException("stripe is down")));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(submitPaymentRequestJson(orderId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, String.valueOf(GlobalExceptionHandler.RETRY_AFTER_SECONDS)))
                .andExpect(jsonPath("$.code").value(GatewayErrorCode.PAYMENT_GATEWAY_UNAVAILABLE.code()))
                .andExpect(jsonPath("$.retryable").value(true));

        verify(paymentService, times(1)).submitPayment(anyLong(), eq(testAmount()));
        verify(httpIdempotencyService, times(1)).release(idempotencyKey);
        verify(httpIdempotencyService, never()).complete(any(), anyInt(), any());
    }

    @Test
    void submitPayment_returns500WithoutItsMessageWhenAnUnexpectedErrorOccurs() throws Exception {
        var orderId = 100L;
        when(paymentService.submitPayment(anyLong(), eq(testAmount()))).thenThrow(new IllegalStateException("unexpected error"));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-3")
                        .content(submitPaymentRequestJson(orderId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(HttpErrorCode.INTERNAL_ERROR.code()))
                .andExpect(jsonPath("$.title").value(HttpErrorCode.INTERNAL_ERROR.title()))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.retryable").value(false));

        verify(paymentService, times(1)).submitPayment(anyLong(), eq(testAmount()));
    }

    @Test
    void submitPayment_returns400WithoutCallingTheServiceWhenIdempotencyKeyIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(HttpErrorCode.MISSING_IDEMPOTENCY_KEY.code()));

        verify(httpIdempotencyService, never()).claim(any(), any(), any());
        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_replaysTheRecordedResponseWithoutCallingTheService() throws Exception {
        var idempotencyKey = "key-replay";
        var recordedResponse = "{\"code\":\"PAYMENT.PAYMENT_GATEWAY_REJECTED\"}";
        var now = Instant.now();
        var record = new HttpRequestRecord(idempotencyKey, "hash", HttpStatus.CONFLICT.value(), recordedResponse, now, now, now);
        when(httpIdempotencyService.claim(eq(idempotencyKey), any(), any(Duration.class)))
                .thenReturn(new RequestAttempt(RequestResult.CACHED, record));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(recordedResponse));

        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_returns422WhenTheIdempotencyKeyWasUsedWithADifferentRequest() throws Exception {
        var idempotencyKey = "key-reused";
        when(httpIdempotencyService.claim(eq(idempotencyKey), any(), any(Duration.class)))
                .thenReturn(new RequestAttempt(RequestResult.HASH_MISMATCH, null));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(HttpErrorCode.IDEMPOTENCY_KEY_REUSED.code()))
                .andExpect(jsonPath("$.retryable").value(false));

        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_returnsARetryable409WhileARequestWithTheSameIdempotencyKeyIsInProgress() throws Exception {
        var idempotencyKey = "key-in-progress";
        when(httpIdempotencyService.claim(eq(idempotencyKey), any(), any(Duration.class)))
                .thenReturn(new RequestAttempt(RequestResult.IN_PROGRESS, null));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, String.valueOf(GlobalExceptionHandler.RETRY_AFTER_SECONDS)))
                .andExpect(jsonPath("$.code").value(HttpErrorCode.REQUEST_IN_PROGRESS.code()))
                .andExpect(jsonPath("$.retryable").value(true));

        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_returns500WhenTheIdempotencyKeyCantBeClaimed() throws Exception {
        var idempotencyKey = "key-store-down";
        when(httpIdempotencyService.claim(eq(idempotencyKey), any(), any(Duration.class)))
                .thenThrow(new IllegalStateException("db down"));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(HttpErrorCode.INTERNAL_ERROR.code()));

        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_returns401WhenNoTokenIsSent() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-4")
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(HttpErrorCode.MISSING_BEARER_TOKEN.code()))
                .andExpect(jsonPath("$.status").value(HttpStatus.UNAUTHORIZED.value()));

        verify(httpIdempotencyService, never()).claim(any(), any(), any());
        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_returns403WhenACustomerCallsItDirectly() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(jwtWithRole("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-5")
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(HttpErrorCode.ACCESS_DENIED.code()))
                .andExpect(jsonPath("$.status").value(HttpStatus.FORBIDDEN.value()));

        verify(httpIdempotencyService, never()).claim(any(), any(), any());
        verifyNoInteractions(paymentService);
    }

    @Test
    void getByOrderId_returns401WhenNoTokenIsSent() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{orderId}", 100L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(HttpErrorCode.MISSING_BEARER_TOKEN.code()));

        verifyNoInteractions(paymentService);
    }

    @Test
    void getByOrderId_returns403WhenTheCallerIsNotAnAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{orderId}", 100L).with(jwtWithRole("CLIENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(HttpErrorCode.ACCESS_DENIED.code()));

        verifyNoInteractions(paymentService);
    }
}
