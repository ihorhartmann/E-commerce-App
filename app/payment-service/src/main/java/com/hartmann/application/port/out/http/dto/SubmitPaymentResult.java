package com.hartmann.application.port.out.http.dto;

public record SubmitPaymentResult(String externalId, String clientSecret) {
}
