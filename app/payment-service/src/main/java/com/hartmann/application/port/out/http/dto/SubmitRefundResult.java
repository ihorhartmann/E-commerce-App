package com.hartmann.application.port.out.http.dto;

import com.hartmann.domain.model.RefundStatus;

public record SubmitRefundResult(String externalId, RefundStatus status) {
}
