package com.hartmann.adapter.in.http.idempotency.dto;

import com.hartmann.application.port.out.idempotency.dto.HttpRequestRecord;

public record RequestAttempt(RequestResult requestResult, HttpRequestRecord record) {
    public static RequestAttempt claimed() {
        return new RequestAttempt(RequestResult.CLAIMED, null);
    }
}
