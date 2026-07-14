package com.hartmann.adapter.in.http.payment.dto;

import java.math.BigDecimal;

public record SubmitPaymentRequest(long orderId, BigDecimal amount, String currency) {
}
