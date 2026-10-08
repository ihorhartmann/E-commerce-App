package com.hartmann.adapter.in.http.payment;

import com.hartmann.application.port.in.PaymentService;
import com.hartmann.adapter.in.http.idempotency.HttpIdempotencyService;
import com.hartmann.adapter.in.http.idempotency.dto.RequestAttempt;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import com.hartmann.infrastructure.config.SecurityConfig;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        var message = "Payment for order " + orderId + " not found";
        when(paymentService.getByOrderId(orderId)).thenThrow(new ObjectNotFoundException(message));

        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId).with(adminJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(message));

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
    }

    @Test
    void submitPayment_returns400WhenADomainRuleIsViolated() throws Exception {
        var orderId = 100L;
        var message = "Unsupported currency: XYZ";
        when(paymentService.submitPayment(anyLong(), eq(testAmount()))).thenThrow(new DomainException(message));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-2")
                        .content(submitPaymentRequestJson(orderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(message));

        verify(paymentService, times(1)).submitPayment(anyLong(), eq(testAmount()));
    }

    @Test
    void submitPayment_returns500WhenAnUnexpectedErrorOccurs() throws Exception {
        var orderId = 100L;
        when(paymentService.submitPayment(anyLong(), eq(testAmount()))).thenThrow(new RuntimeException("unexpected error"));

        mockMvc.perform(post("/api/v1/payments")
                        .with(orderServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-3")
                        .content(submitPaymentRequestJson(orderId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal server error"));

        verify(paymentService, times(1)).submitPayment(anyLong(), eq(testAmount()));
    }

    @Test
    void submitPayment_returns401WhenNoTokenIsSent() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-4")
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }

    @Test
    void submitPayment_returns403WhenACustomerCallsItDirectly() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(jwtWithRole("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-5")
                        .content(submitPaymentRequestJson(100L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService);
    }

    @Test
    void getByOrderId_returns401WhenNoTokenIsSent() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{orderId}", 100L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }

    @Test
    void getByOrderId_returns403WhenTheCallerIsNotAnAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{orderId}", 100L).with(jwtWithRole("CLIENT")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService);
    }
}
