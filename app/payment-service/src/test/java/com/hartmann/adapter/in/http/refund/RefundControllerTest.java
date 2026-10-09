package com.hartmann.adapter.in.http.refund;

import com.hartmann.adapter.in.http.idempotency.HttpIdempotencyService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import com.hartmann.infrastructure.config.SecurityConfig;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RefundController.class, properties = "oidc.roles-claim=realm_access.roles")
@Import(SecurityConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RefundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundService refundService;

    // IdempotencyFilter runs ahead of every controller in this slice too, so it needs a bean here,
    // even though this controller has no mutating endpoint for it to act on.
    @MockitoBean
    private HttpIdempotencyService httpIdempotencyService;

    // SecurityConfig's real decoder would fetch Keycloak's keys; the tests authenticate with jwt() instead
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor jwtWithRole(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static JwtRequestPostProcessor adminJwt() {
        return jwtWithRole("ADMIN");
    }

    private Money testAmount() {
        return new Money(new BigDecimal("20.00"), Currency.USD);
    }

    private Refund testRefund(long id, long paymentId, RefundStatus status) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, UUID.randomUUID(), "re_test", testAmount(), status, 0L, now, now);
    }

    @Test
    void getByOrderId_returns200WithTheRefunds() throws Exception {
        var orderId = 100L;
        var refunds = List.of(testRefund(1L, 1L, RefundStatus.PENDING));
        when(refundService.getByOrderId(orderId)).thenReturn(refunds);

        mockMvc.perform(get("/api/v1/refunds/{orderId}", orderId).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        verify(refundService, times(1)).getByOrderId(orderId);
    }

    @Test
    void getByOrderId_returns401WhenNoTokenIsSent() throws Exception {
        mockMvc.perform(get("/api/v1/refunds/{orderId}", 1L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(refundService);
    }

    @Test
    void getByOrderId_returns403WhenTheCallerIsNotAnAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/refunds/{orderId}", 1L).with(jwtWithRole("CLIENT")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(refundService);
    }
}
