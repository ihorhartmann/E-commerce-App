package com.hartmann.adapter.in.http.refund.dto;

import java.math.BigDecimal;

public record SubmitRefundRequest(long paymentId, BigDecimal amount, String currency) {
}
