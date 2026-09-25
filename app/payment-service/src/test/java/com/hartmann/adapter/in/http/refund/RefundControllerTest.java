package com.hartmann.adapter.in.http.refund;

import com.hartmann.adapter.in.http.IdempotencyFilter;
import com.hartmann.adapter.in.http.idempotency.HttpIdempotencyService;
import com.hartmann.adapter.in.http.idempotency.dto.RequestAttempt;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.application.service.exception.IdempotencyKeyReusedException;
import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefundController.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RefundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundService refundService;

    // IdempotencyFilter runs ahead of every controller in this slice too, so it needs a bean here
    // regardless of whether a given test cares about idempotency itself.
    @MockitoBean
    private HttpIdempotencyService httpIdempotencyService;

    // IdempotencyFilter only runs ahead of the mutating POST endpoint, but stubbing this
    // unconditionally is harmless for the GET test too - Spring's @WebMvcTest doesn't enforce
    // strict/unnecessary-stubbing checks the way MockitoExtension does.
    @BeforeEach
    void stubIdempotencyStart() {
        when(httpIdempotencyService.claim(any(), any(), any(Duration.class))).thenReturn(RequestAttempt.claimed());
    }

    private Money testAmount() {
        return new Money(new BigDecimal("20.00"), Currency.USD);
    }

    private Refund testRefund(long id, long paymentId, RefundStatus status) {
        var now = Instant.now();
        return Refund.createExisting(id, paymentId, UUID.randomUUID(), "re_test", testAmount(), status, now, now);
    }

    private String submitRefundRequestJson(long paymentId) {
        return """
                {"paymentId": %d, "amount": 20.00, "currency": "USD"}
                """.formatted(paymentId);
    }

    @Test
    void getByOrderId_returns200WithTheRefunds() throws Exception {
        var orderId = 100L;
        var refunds = List.of(testRefund(1L, 1L, RefundStatus.PENDING));
        when(refundService.getByOrderId(orderId)).thenReturn(refunds);

        mockMvc.perform(get("/api/v1/refunds/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        verify(refundService, times(1)).getByOrderId(orderId);
    }

    @Test
    void submitRefund_returns201WithTheCreatedRefund() throws Exception {
        var paymentId = 1L;
        var idempotencyKey = UUID.randomUUID();
        var refund = testRefund(1L, paymentId, RefundStatus.PENDING);
        when(refundService.submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount()))).thenReturn(refund);

        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey.toString())
                        .content(submitRefundRequestJson(paymentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(refund.id()))
                .andExpect(jsonPath("$.paymentId").value(paymentId));

        verify(refundService, times(1)).submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount()));
    }

    @Test
    void submitRefund_returns404WhenThePaymentIsNotFound() throws Exception {
        var paymentId = 999L;
        var idempotencyKey = UUID.randomUUID();
        var message = "Payment " + paymentId + " not found";
        when(refundService.submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount())))
                .thenThrow(new ObjectNotFoundException(message));

        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey.toString())
                        .content(submitRefundRequestJson(paymentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(message));

        verify(refundService, times(1)).submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount()));
    }

    @Test
    void submitRefund_returns400WhenADomainRuleIsViolated() throws Exception {
        var paymentId = 1L;
        var idempotencyKey = UUID.randomUUID();
        var message = "Refund amount 20.00 exceeds refundable balance 10.00 for payment " + paymentId;
        when(refundService.submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount()))).thenThrow(new DomainException(message));

        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey.toString())
                        .content(submitRefundRequestJson(paymentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(message));

        verify(refundService, times(1)).submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount()));
    }

    @Test
    void submitRefund_returns409WhenTheIdempotencyKeyBelongsToADifferentRefund() throws Exception {
        var paymentId = 1L;
        var idempotencyKey = UUID.randomUUID();
        var message = "Idempotency key " + idempotencyKey + " was already used for refund 7";
        when(refundService.submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount())))
                .thenThrow(new IdempotencyKeyReusedException(message));

        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey.toString())
                        .content(submitRefundRequestJson(paymentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(message));

        verify(refundService, times(1)).submitRefund(eq(idempotencyKey), isNull(), eq(paymentId), eq(testAmount()));
    }

    @Test
    void submitRefund_returns400WithoutCallingTheServiceWhenTheIdempotencyKeyIsNotAUuid() throws Exception {
        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "key-5")
                        .content(submitRefundRequestJson(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for " + IdempotencyFilter.IDEMPOTENCY_KEY_HEADER));

        verifyNoInteractions(refundService);
    }

    @Test
    void submitRefund_returns500WhenAnUnexpectedErrorOccurs() throws Exception {
        var paymentId = 1L;
        var idempotencyKey = UUID.randomUUID();
        when(refundService.submitRefund(eq(idempotencyKey), isNull(), anyLong(), eq(testAmount()))).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey.toString())
                        .content(submitRefundRequestJson(paymentId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal server error"));

        verify(refundService, times(1)).submitRefund(eq(idempotencyKey), isNull(), anyLong(), eq(testAmount()));
    }
}
