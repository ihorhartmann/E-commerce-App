package com.hartmann.adapter.in.http.webhook;

import com.hartmann.adapter.in.http.idempotency.HttpIdempotencyService;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = StripeWebhookController.class,
        properties = {"oidc.roles-claim=realm_access.roles", "stripe.webhook-secret=whsec_test"}
)
@Import(SecurityConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class StripeWebhookControllerTest {

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

    @Test
    void handleWebhook_returns400WhenTheStripeSignatureHeaderIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Missing Stripe-Signature header"));

        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void handleWebhook_returns400WhenTheStripeSignatureIsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=invalid")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService, refundService);
    }
}
