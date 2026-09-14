package com.hartmann.adapter.in.http.idempotency.dto;

public record RequestFingerprint(String method, String path, byte[] body) {
}
