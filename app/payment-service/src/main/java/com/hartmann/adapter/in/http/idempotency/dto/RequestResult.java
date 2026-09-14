package com.hartmann.adapter.in.http.idempotency.dto;

public enum RequestResult {
    CLAIMED, CACHED, HASH_MISMATCH, IN_PROGRESS
}
