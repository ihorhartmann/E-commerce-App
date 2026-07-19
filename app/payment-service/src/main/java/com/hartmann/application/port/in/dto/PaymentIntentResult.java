package com.hartmann.application.port.in.dto;

import com.hartmann.domain.model.Payment;

public record PaymentIntentResult(Payment payment, String clientSecret) {
}
